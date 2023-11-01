(ns xtdb.s3
  (:require [clojure.spec.alpha :as s]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.file-list :as file-list]
            [xtdb.object-store :as os]
            [xtdb.s3.file-list :as s3-file-watch]
            [xtdb.util :as util])
  (:import [java.io Closeable]
           [java.lang AutoCloseable]
           [java.nio ByteBuffer]
           [java.nio.file Path]
           [java.util ArrayList List NavigableSet]
           [java.util.concurrent CompletableFuture ConcurrentSkipListSet]
           [java.util.function Function]
           [software.amazon.awssdk.core ResponseBytes]
           [software.amazon.awssdk.core.async AsyncRequestBody AsyncResponseTransformer]
           [software.amazon.awssdk.services.s3 S3AsyncClient]
           [software.amazon.awssdk.services.s3.model AbortMultipartUploadRequest CompleteMultipartUploadRequest CompletedPart CompletedMultipartUpload CreateMultipartUploadRequest CreateMultipartUploadResponse DeleteObjectRequest GetObjectRequest HeadObjectRequest NoSuchKeyException PutObjectRequest UploadPartRequest UploadPartResponse]
           [xtdb.object_store ObjectStore SupportsMultipart IMultipartUpload]
           [xtdb.s3 S3Configurator]))

(defn- get-obj-req
  ^GetObjectRequest [{:keys [^S3Configurator configurator bucket ^Path prefix]} ^Path k]
  (let [prefixed-key (util/prefix-key prefix k)]
    (-> (GetObjectRequest/builder)
        (.bucket bucket)
        (.key (str prefixed-key))
        (->> (.configureGet configurator))
        ^GetObjectRequest (.build))))

(defn- get-obj-range-req
  ^GetObjectRequest [{:keys [^S3Configurator configurator bucket ^Path prefix]} ^Path k ^Long start ^long len]
  (let [prefixed-key (util/prefix-key prefix k)
        end-byte (+ start (dec len))]
    (-> (GetObjectRequest/builder)
        (.bucket bucket)
        (.key (str prefixed-key))
        (.range (format "bytes=%d-%d" start end-byte))
        (->> (.configureGet configurator))
        ^GetObjectRequest (.build))))

(defn- with-exception-handler [^CompletableFuture fut ^Path k]
  (.exceptionally fut (reify Function
                        (apply [_ e]
                          (try
                            (throw (.getCause ^Exception e))
                            (catch NoSuchKeyException _
                              (throw (os/obj-missing-exception k))))))))

(defn single-object-upload
  [{:keys [^S3AsyncClient client ^S3Configurator configurator bucket ^Path prefix]} ^Path k ^ByteBuffer buf]
  (let [prefixed-key (util/prefix-key prefix k)]
    (.putObject client
                (-> (PutObjectRequest/builder)
                    (.bucket bucket)
                    (.key (str prefixed-key))
                    (->> (.configurePut configurator))
                    ^PutObjectRequest (.build))
                (AsyncRequestBody/fromByteBuffer buf))))

(defrecord MultipartUpload [^S3AsyncClient client bucket ^Path prefix ^Path k upload-id on-complete ^List !completed-parts]
  IMultipartUpload 
  (uploadPart [_  buf]
    (let [prefixed-key (util/prefix-key prefix k)
          content-length (long (.limit buf))
          part-number (int (inc (.size !completed-parts)))]
      (-> (.uploadPart client
                       (-> (UploadPartRequest/builder)
                           (.bucket bucket)
                           (.key (str prefixed-key))
                           (.uploadId upload-id)
                           (.partNumber part-number)
                           (.contentLength content-length)
                           ^UploadPartRequest (.build))
                       (AsyncRequestBody/fromByteBuffer buf))
          (util/then-apply (fn [^UploadPartResponse upload-part-response]
                             (.add !completed-parts (-> (CompletedPart/builder)
                                                        (.partNumber part-number)
                                                        (.eTag (.eTag upload-part-response))
                                                        ^CompletedPart (.build))))))))
  
  (complete [_]
    (let [prefixed-key (util/prefix-key prefix k)]
      (-> (.completeMultipartUpload client
                                    (-> (CompleteMultipartUploadRequest/builder)
                                        (.bucket bucket)
                                        (.key (str prefixed-key))
                                        (.uploadId upload-id)
                                        (.multipartUpload (-> (CompletedMultipartUpload/builder)
                                                              (.parts !completed-parts)
                                                              ^CompletedMultipartUpload (.build)))
                                        ^CompleteMultipartUploadRequest (.build)))
          (.thenRun (fn [] (on-complete k))))))
  
  (abort [_]
    (let [prefixed-key (util/prefix-key prefix k)]
      (.abortMultipartUpload client
                             (-> (AbortMultipartUploadRequest/builder)
                                 (.bucket bucket)
                                 (.key (str prefixed-key))
                                 (.uploadId upload-id)
                                 ^AbortMultipartUploadRequest (.build))))))

(defrecord S3ObjectStore [^S3Configurator configurator ^S3AsyncClient client bucket ^Path prefix multipart-minimum-part-size ^NavigableSet file-name-cache ^AutoCloseable file-list-watcher]
  ObjectStore
  (getObject [this k]
    (-> (.getObject client (get-obj-req this k) (AsyncResponseTransformer/toBytes))
        (.thenApply (reify Function
                      (apply [_ bs]
                        (.asByteBuffer ^ResponseBytes bs))))
        (with-exception-handler k)))

  (getObject [this k out-path]
    (-> (.getObject client (get-obj-req this k) out-path)
        (.thenApply (reify Function
                      (apply [_ _]
                        out-path)))
        (with-exception-handler k)))

  (getObjectRange [this k start len]
    (os/ensure-shared-range-oob-behaviour start len)
    (try
      (-> (.getObject client ^GetObjectRequest (get-obj-range-req this k start len) (AsyncResponseTransformer/toBytes))
          (.thenApply (reify Function
                        (apply [_ bs]
                          (.asByteBuffer ^ResponseBytes bs))))
          (with-exception-handler k))
      (catch IndexOutOfBoundsException e
        (CompletableFuture/failedFuture e))))

  (putObject [this k buf]
    (let [prefixed-key (util/prefix-key prefix k)]
      (-> (.headObject client
                       (-> (HeadObjectRequest/builder)
                           (.bucket bucket)
                           (.key (str prefixed-key))
                           (->> (.configureHead configurator))
                           ^HeadObjectRequest (.build)))
          (util/then-apply (fn [_resp] true))
          (.exceptionally (reify Function
                            (apply [_ e]
                              (let [e (.getCause ^Exception e)]
                                (if (instance? NoSuchKeyException e)
                                  false
                                  (throw e))))))
          (util/then-compose (fn [exists?]
                               (if exists?
                                 (CompletableFuture/completedFuture nil)
                                 (single-object-upload this k buf))))
          (util/then-apply (fn [_]
                           ;; Add file name to the local cache as the last thing we do (ie - if PUT
                           ;; fails, shouldnt add filename to the cache)
                             (.add file-name-cache k))))))

  (listObjects [_this]
    (into [] file-name-cache))

  (listObjects [_this dir]
    (file-list/list-files-under-prefix file-name-cache dir))

  (deleteObject [_ k]
    (let [prefixed-key (util/prefix-key prefix k)]
      (.remove file-name-cache k)
      (.deleteObject client
                     (-> (DeleteObjectRequest/builder)
                         (.bucket bucket)
                         (.key (str prefixed-key))
                         ^DeleteObjectRequest (.build)))))

  SupportsMultipart
  (startMultipart [_ k]
    (let [prefixed-key (util/prefix-key prefix k)
          initiate-request (-> (CreateMultipartUploadRequest/builder)
                               (.bucket bucket)
                               (.key (str prefixed-key))
                               ^CreateMultipartUploadRequest (.build))]
      (-> (.createMultipartUpload client initiate-request)
          (util/then-apply (fn [^CreateMultipartUploadResponse initiate-response]
                             (->MultipartUpload client
                                                bucket
                                                prefix
                                                k
                                                (.uploadId initiate-response)
                                                (fn [k]
                                                  ;; On complete - add filename to cache
                                                  (.add file-name-cache k))
                                                (ArrayList.)))))))

  Closeable
  (close [_]
    (.close file-list-watcher)
    (.clear file-name-cache)
    (.close client)))

(s/def ::configurator #(instance? S3Configurator %))
(s/def ::bucket string?)
(s/def ::prefix ::util/path)
(s/def ::sns-topic-arn string?)

(defmethod ig/prep-key ::object-store [_ opts]
  (-> (merge {:configurator (reify S3Configurator)}
             opts)
      (util/maybe-update :prefix util/->path)))

(defmethod ig/pre-init-spec ::object-store [_]
  (s/keys :req-un [::configurator ::bucket ::sns-topic-arn]
          :opt-un [::prefix]))

(def minimum-part-size (* 5 1024 1024))

(defmethod ig/init-key ::object-store [_ {:keys [bucket ^Path prefix ^S3Configurator configurator] :as opts}]
  (let [s3-client (.makeClient configurator)
        file-name-cache (ConcurrentSkipListSet.)
        ;; Watch s3 bucket for changes
        file-list-watcher (s3-file-watch/open-file-list-watcher (assoc opts :s3-client s3-client)
                                                                file-name-cache)]

    (->S3ObjectStore configurator
                     s3-client
                     bucket
                     prefix
                     minimum-part-size
                     file-name-cache
                     file-list-watcher)))

(defmethod ig/halt-key! ::object-store [_ os]
  (util/try-close os))

(derive ::object-store :xtdb/object-store)
