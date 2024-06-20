(ns xtdb.sql.expr-test
  (:require [clojure.test :as t]
            [xtdb.api :as xt]
            [xtdb.sql.plan :as plan]
            [xtdb.sql-test :as sql-test]
            [xtdb.test-util :as tu])
  (:import (java.time.zone ZoneRulesException)
           [java.util HashMap]))

(t/use-fixtures :each tu/with-mock-clock tu/with-node)

(defn plan-expr-with-foo [expr]
  (plan/plan-expr expr {:scope (plan/map->BaseTable (-> '{:table-name foo
                                                          :table-alias foo
                                                          :unique-table-alias f
                                                          :cols #{a b}}
                                                        (assoc :!reqd-cols (HashMap.))))}))

(t/deftest test-trim-expr
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))

    "TRIM(foo.a)" '(trim f/a " ")

    "TRIM(LEADING FROM foo.a)" '(trim-leading f/a " ")
    "TRIM(LEADING '$' FROM foo.a)" '(trim-leading f/a "$")
    "TRIM(LEADING foo.b FROM foo.a)" '(trim-leading f/a f/b)

    "TRIM(TRAILING FROM foo.a)" '(trim-trailing f/a " ")
    "TRIM(TRAILING '$' FROM foo.a)" '(trim-trailing f/a "$")
    "TRIM(TRAILING foo.b FROM foo.a)" '(trim-trailing f/a f/b)

    "TRIM(BOTH FROM foo.a)" '(trim f/a " ")
    "TRIM(BOTH '$' FROM foo.a)" '(trim f/a "$")
    "TRIM(BOTH foo.b FROM foo.a)" '(trim f/a f/b)

    "TRIM(BOTH '😎' FROM foo.a)" '(trim f/a "😎")

    "TRIM('$' FROM foo.a)" '(trim f/a "$")))

(t/deftest test-like-expr
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))

    "foo.a LIKE ''" '(like f/a "")
    "foo.a LIKE foo.b" '(like f/a f/b)
    "foo.a LIKE 'foo%'" '(like f/a "foo%")

    "foo.a NOT LIKE ''" '(not (like f/a ""))
    "foo.a NOT LIKE foo.b" '(not (like f/a f/b))
    "foo.a NOT LIKE 'foo%'" '(not (like f/a "foo%"))

    ;; no support for ESCAPE (or default escapes), see #157
    ))

(t/deftest test-like-regex-expr
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))

    "foo.a LIKE_REGEX foo.b" '(like-regex f/a f/b "")
    "foo.a LIKE_REGEX foo.b FLAG 'i'" '(like-regex f/a f/b "i")

    "foo.a NOT LIKE_REGEX foo.b" '(not (like-regex f/a f/b ""))
    "foo.a NOT LIKE_REGEX foo.b FLAG 'i'" '(not (like-regex f/a f/b "i"))))

(t/deftest test-like-regex-query-case-insensitive
  (t/is (= [{:match false}]
           (xt/q tu/*node* "SELECT ('ABC' LIKE_REGEX 'a') as match")))

  (t/is (= [{:match true}]
           (xt/q tu/*node* "SELECT ('ABC' LIKE_REGEX 'a' FLAG 'i') as match"))))

(t/deftest test-postgres-regex-expr
  (t/are [sql expected] (= expected (plan-expr-with-foo sql))

    "foo.a ~ foo.b" '(like-regex f/a f/b "")
    "foo.a ~* foo.b" '(like-regex f/a f/b "i")

    "foo.a !~ foo.b" '(not (like-regex f/a f/b ""))
    "foo.a !~* foo.b" '(not (like-regex f/a f/b "i"))))

(defn pg-regex-query [val op pattern]
  (let [query (format "SELECT ('%s' %s '%s') as match" val op pattern)
        [{:keys [match]}] (xt/q tu/*node* query)]
    match))

(t/deftest test-postgres-regex-query
  (t/testing "postgres case sensitive match regex"
    (t/are [expected val op pattern] (= expected (pg-regex-query val op pattern))
      true "abcd" "~" "abcd"
      true "abcd" "~" "a.c"
      false "abcd" "~" "x"
      false "abcd" "~" "A"
      false "ABCD" "~" "a"))

  (t/testing "postgres case insensitive match regex"
    (t/are [expected val op pattern] (= expected (pg-regex-query val op pattern))
      true "abcd" "~*" "abcd"
      true "abcd" "~*" "a.c"
      false "abcd" "~*" "x"
      true "abcd" "~*" "A"
      true "ABCD" "~*" "a"))

  (t/testing "postgres case sensitive not match regex"
    (t/are [expected val op pattern] (= expected (pg-regex-query val op pattern))
      false "abcd" "!~" "abcd"
      false "abcd" "!~" "a.c"
      true "abcd" "!~" "x"
      true "abcd" "!~" "A"
      true "ABCD" "!~" "a"))

  (t/testing "postgres case insensitive not match regex"
    (t/are [expected val op pattern] (= expected (pg-regex-query val op pattern))
      false "abcd" "!~*" "abcd"
      false "abcd" "!~*" "a.c"
      true "abcd" "!~*" "x"
      false "abcd" "!~*" "A"
      false "ABCD" "!~*" "a")))

(t/deftest test-upper-expr
  (t/is (= '(upper f/a) (plan-expr-with-foo "UPPER(foo.a)"))))

(t/deftest test-lower-expr
  (t/is (= '(lower f/a) (plan-expr-with-foo "LOWER(foo.a)"))))

(t/deftest test-substring-expr
  (t/are
   [sql expected] (= expected (plan-expr-with-foo sql))
    "SUBSTRING(foo.a FROM 1)" '(substring f/a 1)
    "SUBSTRING(foo.a FROM 1 FOR 2)" '(substring f/a 1 2)
    "SUBSTRING(foo.a FROM 1 USING CHARACTERS)" '(substring f/a 1)))

(t/deftest test-concat-expr
  (t/is (= '(concat f/a f/b) (plan-expr-with-foo "foo.a || foo.b")))
  (t/is (= '(concat "a" f/b) (plan-expr-with-foo "'a' || foo.b")))
  (t/is (= '(concat (concat f/a "a") "b") (plan-expr-with-foo "foo.a || 'a' || 'b'"))))

(t/deftest test-character-length-expr
  (t/is (= '(character-length f/a) (plan-expr-with-foo "CHARACTER_LENGTH(foo.a)")))
  (t/is (= '(character-length f/a) (plan-expr-with-foo "CHARACTER_LENGTH(foo.a USING CHARACTERS)")))
  (t/is (= '(octet-length f/a) (plan-expr-with-foo "CHARACTER_LENGTH(foo.a USING OCTETS)"))))

(t/deftest test-char-length-alias
  (t/is (= '(character-length f/a) (plan-expr-with-foo "CHAR_LENGTH(foo.a)")) "CHAR_LENGTH alias works")
  (t/is (= '(character-length f/a) (plan-expr-with-foo "CHAR_LENGTH(foo.a USING CHARACTERS)")) "CHAR_LENGTH alias works")
  (t/is (= '(octet-length f/a) (plan-expr-with-foo "CHAR_LENGTH(foo.a USING OCTETS)")) "CHAR_LENGTH alias works"))

(t/deftest test-octet-length-expr
  (t/is (= '(octet-length f/a) (plan-expr-with-foo "OCTET_LENGTH(foo.a)"))))

(t/deftest test-position-expr
  (t/is (= '(position f/a f/b) (plan-expr-with-foo "POSITION(foo.a IN foo.b)")))
  (t/is (= '(position f/a f/b) (plan-expr-with-foo "POSITION(foo.a IN foo.b USING CHARACTERS)")))
  (t/is (= '(octet-position f/a f/b) (plan-expr-with-foo "POSITION(foo.a IN foo.b USING OCTETS)"))))

(t/deftest test-length-expr
  (t/is (= '(length f/a) (plan-expr-with-foo "LENGTH(foo.a)")))
  (t/is (= '(length "abc") (plan-expr-with-foo "LENGTH('abc')")))
  (t/is (= '(length [1 2 3]) (plan-expr-with-foo "LENGTH([1, 2, 3])"))))

(t/deftest test-length-query
  (xt/submit-tx tu/*node* [[:put-docs :docs {:xt/id 1
                                             :string "abcdef"
                                             :list [1 2 3 4 5]
                                             :map {:a 1 :b 2}
                                             :setval #{1 2 3}
                                             :varbinary (byte-array [11 22])}]])

  (t/is (= [{:len 3}] (xt/q tu/*node* "SELECT LENGTH('abc') as len FROM docs")))
  (t/is (= [{:len 6}] (xt/q tu/*node* "SELECT LENGTH(docs.string) as len FROM docs")))
  (t/is (= [{:len 4}] (xt/q tu/*node* "SELECT LENGTH([1,2,3,4]) as len FROM docs")))
  (t/is (= [{:len 5}] (xt/q tu/*node* "SELECT LENGTH(docs.list) as len FROM docs")))
  (t/is (= [{:len 2}] (xt/q tu/*node* "SELECT LENGTH(docs.map) as len FROM docs")))
  (t/is (= [{:len 3}] (xt/q tu/*node* "SELECT LENGTH(docs.setval) as len FROM docs")))
  (t/is (= [{:len 2}] (xt/q tu/*node* "SELECT LENGTH(docs.varbinary) as len FROM docs"))))

(t/deftest test-numerical-fn-exprs
  (t/are [expr expected]
         (= expected (plan-expr-with-foo expr))
    "CARDINALITY(foo.a)" '(cardinality f/a)
    "ABS(foo.a)" '(abs f/a)
    "MOD(foo.a, foo.b)" '(mod f/a f/b)
    "SIN(foo.a)" '(sin f/a)
    "COS(foo.a)" '(cos f/a)
    "TAN(foo.a)" '(tan f/a)
    "LOG(foo.a, 3)" '(log f/a 3)
    "LOG10(foo.a)" '(log10 f/a)
    "LN(foo.a)" '(ln f/a)
    "EXP(foo.a)" '(exp f/a)
    "POWER(foo.a, 3)" '(power f/a 3)
    "SQRT(foo.a)" '(sqrt f/a)
    "FLOOR(foo.a)" '(floor f/a)
    "CEIL(foo.a)" '(ceil f/a)
    "LEAST(foo.a, foo.b)" '(least f/a f/b)
    "GREATEST(foo.a, foo.b)" '(greatest f/a f/b)))

(t/deftest test-interval-abs
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql)) 
    "ABS(INTERVAL '1' YEAR)" '(abs (single-field-interval "1" "YEAR" 2 6))))

(t/deftest test-boolean-predicate-exprs
  (t/are [expr expected]
         (= expected (plan-expr-with-foo expr))
    "1 > 2" '(> 1 2)
    "1 >= 2" '(>= 1 2)
    "1 < 2" '(< 1 2)
    "1 <= 2" '(<= 1 2)
    "1 = 2" '(= 1 2)
    "1 != 2" '(!= 1 2)
    "1 <> 2" '(<> 1 2)
    "2 BETWEEN 1 AND 3" '(between 2 1 3)
    "2 BETWEEN ASYMMETRIC 1 AND 3" '(between 2 1 3)
    "2 BETWEEN SYMMETRIC 1 AND 3" '(between-symmetric 2 1 3)
    "2 NOT BETWEEN 1 AND 3" '(not (between 2 1 3))))

(t/deftest test-overlay-expr
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))
    "OVERLAY(foo.a PLACING foo.b FROM 1 for 4)" '(overlay f/a f/b 1 4)
    "OVERLAY(foo.a PLACING foo.b FROM 1)" '(overlay f/a f/b 1 (default-overlay-length f/b))))

(t/deftest test-bool-test-expr
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))

    "foo.a IS true" '(true? f/a)
    "foo.a IS NOT true" '(not (true? f/a))

    "foo.a IS false" '(false? f/a)
    "foo.a IS NOT false" '(not (false? f/a))

    "foo.a IS UNKNOWN" '(nil? f/a)
    "foo.a IS NOT UNKNOWN" '(not (nil? f/a))

    "foo.a IS NULL" '(nil? f/a)
    "foo.a IS NOT NULL" '(not (nil? f/a))))

(t/deftest test-interval-exprs
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))
    "INTERVAL '1' YEAR + INTERVAL '3' MONTH + INTERVAL '4' DAY" '(+ (+ (single-field-interval "1" "YEAR" 2 6)
                                                                       (single-field-interval "3" "MONTH" 2 6))
                                                                    (single-field-interval "4" "DAY" 2 6))
    
    "INTERVAL '1' YEAR * 3" '(* (single-field-interval "1" "YEAR" 2 6) 3)
    "3 * INTERVAL '1' YEAR" '(* 3 (single-field-interval "1" "YEAR" 2 6))

    "INTERVAL '1' YEAR / 3" '(/ (single-field-interval "1" "YEAR" 2 6) 3)
    "INTERVAL '3' YEAR" '(single-field-interval "3" "YEAR" 2 6)
    "INTERVAL '-3' YEAR" '(single-field-interval "-3" "YEAR" 2 6)
    "INTERVAL '+3' YEAR" '(single-field-interval "+3" "YEAR" 2 6)

    "INTERVAL '3' MONTH" '(single-field-interval "3" "MONTH" 2 6)
    "INTERVAL '-3' MONTH" '(single-field-interval "-3" "MONTH" 2 6)
    "INTERVAL '+3' MONTH" '(single-field-interval "+3" "MONTH" 2 6)

    "INTERVAL '3' DAY" '(single-field-interval "3" "DAY" 2 6)
    "INTERVAL '-3' DAY" '(single-field-interval "-3" "DAY" 2 6)
    "INTERVAL '+3' DAY" '(single-field-interval "+3" "DAY" 2 6)
    "INTERVAL '333' DAY(3)" '(single-field-interval "333" "DAY" 2 6)

    "INTERVAL '3' HOUR" '(single-field-interval "3" "HOUR" 2 6)
    "INTERVAL '-3' HOUR" '(single-field-interval "-3" "HOUR" 2 6)
    "INTERVAL '+3' HOUR" '(single-field-interval "+3" "HOUR" 2 6)

    "INTERVAL '3' MINUTE" '(single-field-interval "3" "MINUTE" 2 6)
    "INTERVAL '-3' MINUTE" '(single-field-interval "-3" "MINUTE" 2 6)
    "INTERVAL '+3' MINUTE" '(single-field-interval "+3" "MINUTE" 2 6)

    "INTERVAL '3' SECOND" '(single-field-interval "3" "SECOND" 2 6)
    "INTERVAL '-3' SECOND" '(single-field-interval "-3" "SECOND" 2 6)
    "INTERVAL '+3' SECOND" '(single-field-interval "+3" "SECOND" 2 6)
    "INTERVAL '333' SECOND(3)" '(single-field-interval "333" "SECOND" 2 3)

    "INTERVAL '3-4' YEAR TO MONTH" '(multi-field-interval "3-4" "YEAR" 2 "MONTH" 6)
    "INTERVAL '-3-4' YEAR TO MONTH" '(multi-field-interval "-3-4" "YEAR" 2 "MONTH" 6)
    "INTERVAL '+3-4' YEAR TO MONTH" '(multi-field-interval "+3-4" "YEAR" 2 "MONTH" 6)

    "INTERVAL '3 4' DAY TO HOUR" '(multi-field-interval "3 4" "DAY" 2 "HOUR" 6)
    "INTERVAL '3 04' DAY TO HOUR" '(multi-field-interval "3 04" "DAY" 2 "HOUR" 6)
    "INTERVAL '3 04:20' DAY TO MINUTE" '(multi-field-interval "3 04:20" "DAY" 2 "MINUTE" 6)
    "INTERVAL '3 04:20:34' DAY TO SECOND" '(multi-field-interval "3 04:20:34" "DAY" 2 "SECOND" 6)
    "INTERVAL '3 04:20:34' DAY TO SECOND(4)" '(multi-field-interval "3 04:20:34" "DAY" 2 "SECOND" 4)

    "INTERVAL '04:20' HOUR TO MINUTE" '(multi-field-interval "04:20" "HOUR" 2 "MINUTE" 6)
    "INTERVAL '04:20:34' HOUR TO SECOND" '(multi-field-interval "04:20:34" "HOUR" 2 "SECOND" 6)

    "INTERVAL '20:34' MINUTE TO SECOND" '(multi-field-interval "20:34" "MINUTE" 2 "SECOND" 6)

    "INTERVAL -'3' YEAR" '(- (single-field-interval "3" "YEAR" 2 6))
    "INTERVAL -'3-10' YEAR TO MONTH" '(- (multi-field-interval "3-10" "YEAR" 2 "MONTH" 6))
    "INTERVAL -'3 10' DAY TO HOUR" '(- (multi-field-interval "3 10" "DAY" 2 "HOUR" 6))
    
    "CAST(foo.a AS INTERVAL)" '(cast f/a :interval)
    "CAST(foo.a AS INTERVAL YEAR)" '(cast f/a :interval {:start-field "YEAR",
                                                         :end-field nil,
                                                         :leading-precision 2,
                                                         :fractional-precision 6})))

(t/deftest test-interval-comparison
  (t/is (= [{:gt true}]
           (xt/q tu/*node* "SELECT (INTERVAL '3 4' DAY TO HOUR > INTERVAL '3 1' DAY TO HOUR) as gt")))

  (t/is (= [{:lt false}]
           (xt/q tu/*node* "SELECT (INTERVAL '3 4' DAY TO HOUR < INTERVAL '3 1' DAY TO HOUR) as lt")))

  (t/is (= [{:gte true}]
           (xt/q tu/*node* "SELECT (INTERVAL '3 4' DAY TO HOUR >= INTERVAL '3 1' DAY TO HOUR) as gte")))

  (t/is (= [{:lte false}]
           (xt/q tu/*node* "SELECT (INTERVAL '3 4' DAY TO HOUR <= INTERVAL '3 1' DAY TO HOUR) as lte")))

  (t/is (= [{:eq false}]
           (xt/q tu/*node* "SELECT (INTERVAL '3 4' DAY TO HOUR = INTERVAL '3 1' DAY TO HOUR) as eq")))

  (t/is (= [{:eq true}]
           (xt/q tu/*node* "SELECT (INTERVAL '3 1' DAY TO HOUR = INTERVAL '3 1' DAY TO HOUR) as eq"))))

(t/deftest test-array-construction
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))

    "ARRAY []" []

    "ARRAY [1]" [1]
    "ARRAY [NULL]" [nil]
    "ARRAY [ARRAY [1]]" [[1]]

    "ARRAY [foo.a, foo.b + 1]" '[f/a (+ f/b 1)]

    "ARRAY [1, 42]" [1 42]
    "ARRAY [1, NULL]" [1 nil]
    "ARRAY [1, 1.2, '42!']" [1 1.2 "42!"]

    "[]" []

    "[1]" [1]
    "[NULL]" [nil]
    "[[1]]" [[1]]

    "[foo.a, foo.b + 1]" '[f/a (+ f/b 1)]

    "[1, 42]" [1 42]
    "[1, NULL]" [1 nil]
    "[1, 1.2, '42!']" [1 1.2 "42!"]))

(t/deftest test-object-construction
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))

    "OBJECT ()" {}
    "OBJECT (foo: 2)" {:foo 2}
    "OBJECT (foo: 2, bar: true)" {:foo 2 :bar true}
    "OBJECT (foo: 2, bar: ARRAY [true, 1])" {:foo 2 :bar [true 1]}
    "OBJECT (foo: 2, bar: OBJECT(baz: ARRAY [true, 1]))" {:foo 2 :bar {:baz [true 1]}}

    "{}" {}
    "{foo: 2}" {:foo 2}
    "{foo: 2, bar: true}" {:foo 2 :bar true}
    "{foo: 2, bar: [true, 1]}" {:foo 2 :bar [true 1]}
    "{foo: 2, bar: {baz: [true, 1]}}" {:foo 2 :bar {:baz [true 1]}}))

(t/deftest test-object-field-access
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))

    "OBJECT(foo: 2).foo" '(. {:foo 2} :foo)
    "{foo: 2}.foo" '(. {:foo 2} :foo)
    "{foo: 2}.foo.bar" '(. (. {:foo 2} :foo) :bar)

    ;; currently required to wrap field accesses in parens - Postgres does this too, so it's not a cardinal sin,
    ;; but I guess it'd be nice to resolve this in the future
    "(foo.a).b" '(. f/a :b)
    "(foo.a).b.c" '(. (. f/a :b) :c)))

(t/deftest test-array-expressions
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))

    "[1,2]" [1 2]
    "[1,2] || foo.a" '(concat [1 2] f/a)
    "[1,2] || [2,3]" '(concat [1 2] [2 3])))

(t/deftest test-array-trim
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))

    "TRIM_ARRAY(NULL, 2)" '(trim-array nil 2)
    "TRIM_ARRAY(foo.a, 2)" '(trim-array f/a 2)
    "TRIM_ARRAY(ARRAY [42, 43], 1)" '(trim-array [42, 43] 1)
    "TRIM_ARRAY(foo.a, foo.b)" '(trim-array f/a f/b)))

(t/deftest test-cast
  (t/are [sql expected]
         (= expected (plan-expr-with-foo sql))

    "CAST(NULL AS INT)" '(cast nil :i32)
    "CAST(NULL AS INTEGER)" '(cast nil :i32)
    "CAST(NULL AS BIGINT)" '(cast nil :i64)
    "CAST(NULL AS SMALLINT)" '(cast nil :i16)
    "CAST(NULL AS FLOAT)" '(cast nil :f32)
    "CAST(NULL AS REAL)" '(cast nil :f32)
    "CAST(NULL AS DOUBLE PRECISION)" '(cast nil :f64)
    "CAST(foo.a AS INT)" '(cast f/a :i32)
    "CAST(42.0 AS INT)" '(cast 42.0 :i32)))

(t/deftest test-postgres-cast-syntax
  (t/testing "planning"
    (t/are [sql expected]
           (= expected (plan-expr-with-foo sql))

      "NULL::INT" '(cast nil :i32)
      "foo.a::INT" '(cast f/a :i32)
      "'42.0'::FLOAT" '(cast "42.0" :f32)))
  
  (t/testing "used within a query"
    (t/is (= [{:x 42}]
             (xt/q tu/*node* "SELECT '42'::INT as x")))
    
    (t/is (= [{:x #xt.time/date "2021-10-21"}]
             (xt/q tu/*node* "SELECT '2021-10-21'::DATE as x")))))

(t/deftest test-cast-string-to-temporal
  (t/is (= [{:timestamp-tz #xt.time/zoned-date-time "2021-10-21T12:34:00Z"}]
           (xt/q tu/*node* "SELECT CAST('2021-10-21T12:34:00Z' AS TIMESTAMP WITH TIME ZONE) as timestamp_tz")))

  (t/is (= [{:timestamp #xt.time/date-time "2021-10-21T12:34:00"}]
           (xt/q tu/*node* "SELECT CAST('2021-10-21T12:34:00' AS TIMESTAMP) as \"timestamp\"")))

  (t/is (= [{:timestamp #xt.time/date-time "2021-10-21T12:34:00"}]
           (xt/q tu/*node* "SELECT CAST('2021-10-21T12:34:00' AS TIMESTAMP WITHOUT TIME ZONE) as \"timestamp\"")))

  (t/is (= [{:duration #xt.time/date "2021-10-21"}]
           (xt/q tu/*node* "SELECT CAST('2021-10-21' AS DATE) as \"duration\"")))

  (t/is (= [{:time #xt.time/time "12:00:01"}]
           (xt/q tu/*node* "SELECT CAST('12:00:01' AS TIME) as \"time\"")))

  (t/is (= [{:duration #xt.time/duration "PT13M56.123456S"}]
           (xt/q tu/*node* "SELECT CAST('PT13M56.123456789S' AS DURATION) as \"duration\"")))

  (t/is (= [{:duration #xt.time/duration "PT13M56.123456789S"}]
           (xt/q tu/*node* "SELECT CAST('PT13M56.123456789S' AS DURATION(9)) as \"duration\"")))

  (t/is (= [{:time #xt.time/time "12:00:01.1234"}]
           (xt/q tu/*node* "SELECT CAST('12:00:01.123456' AS TIME(4)) as \"time\"")))

  (t/is (= [{:timestamp #xt.time/date-time "2021-10-21T12:34:00.1234567"}]
           (xt/q tu/*node* "SELECT CAST('2021-10-21T12:34:00.123456789' AS TIMESTAMP(7)) as \"timestamp\"")))

  (t/is (= [{:timestamp-tz #xt.time/zoned-date-time "2021-10-21T12:34:00.12Z"}]
           (xt/q tu/*node* "SELECT CAST('2021-10-21T12:34:00.123Z' AS TIMESTAMP(2) WITH TIME ZONE) as timestamp_tz")))

  (t/is (thrown-with-msg?
         RuntimeException
         #"String '2021-10-21T12' has invalid format for type timestamp with timezone"
         (xt/q tu/*node* "SELECT CAST('2021-10-21T12' AS TIMESTAMP WITH TIME ZONE) as timestamp_tz"))))

(t/deftest test-cast-temporal-to-string
  (t/is (= [{:string "2021-10-21T12:34:01Z"}]
           (xt/q tu/*node* "SELECT CAST(TIMESTAMP '2021-10-21T12:34:01Z' AS VARCHAR) as string")))

  (t/is (= [{:string "2021-10-21T12:34:01"}]
           (xt/q tu/*node* "SELECT CAST(TIMESTAMP '2021-10-21T12:34:01' AS VARCHAR) as string")))

  (t/is (= [{:string "2021-10-21"}]
           (xt/q tu/*node* "SELECT CAST(DATE '2021-10-21' AS VARCHAR) as string")))

  (t/is (= [{:string "12:00:01"}]
           (xt/q tu/*node* "SELECT CAST(TIME '12:00:01' AS VARCHAR) as string")))

  (t/is (= [{:string "PT13M56.123S"}]
           (xt/q tu/*node* "SELECT CAST(DURATION 'PT13M56.123S' AS VARCHAR) as string"))))

(t/deftest test-cast-interval-to-duration
  (t/is (= [{:duration #xt.time/duration "PT13M56S"}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '13:56' MINUTE TO SECOND AS DURATION) as \"duration\"")))

  (t/is (= [{:duration #xt.time/duration "PT13M56.123456789S"}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '13:56.123456789' MINUTE TO SECOND AS DURATION(9)) as \"duration\""))))

(t/deftest test-cast-duration-to-interval
  (t/testing "without interval qualifier"
    (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT26H13M56.111111S"]}]
             (xt/q tu/*node* "SELECT CAST(DURATION 'PT26H13M56.111111S' AS INTERVAL) as itvl")))

    (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT122H"]}]
             (xt/q tu/*node* "SELECT CAST((TIMESTAMP '2021-10-26T14:00:00' - TIMESTAMP '2021-10-21T12:00:00') AS INTERVAL) as itvl")))

    (t/is (= [{:itvl #xt/interval-mdn  ["P0D" "PT8882H"]}]
             (xt/q tu/*node* "SELECT CAST((TIMESTAMP '2021-10-26T14:00:00' - TIMESTAMP '2020-10-21T12:00:00') AS INTERVAL) as itvl"))))

  (t/testing "with interval qualifier"
    (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT0S"]}]
             (xt/q tu/*node* "SELECT CAST(DURATION 'PT26H13M56.111111S' AS INTERVAL DAY) as itvl")))

    (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT2H"]}]
             (xt/q tu/*node* "SELECT CAST(DURATION 'PT26H13M56.111111S' AS INTERVAL DAY TO HOUR) as itvl")))

    (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT2H13M"]}]
             (xt/q tu/*node* "SELECT CAST(DURATION 'PT26H13M56.111111S' AS INTERVAL DAY TO MINUTE) as itvl")))

    (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT2H13M56.111111S"]}]
             (xt/q tu/*node* "SELECT CAST(DURATION 'PT26H13M56.111111S' AS INTERVAL DAY TO SECOND) as itvl")))

    (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT2H13M56.111S"]}]
             (xt/q tu/*node* "SELECT CAST(DURATION 'PT26H13M56.111111S' AS INTERVAL DAY TO SECOND(3)) as itvl")))))

(t/deftest test-cast-interval-to-interval
  (t/is (= [{:itvl #xt/interval-ym "P22M"}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1-10' YEAR TO MONTH AS INTERVAL) as itvl")))

  (t/is (= [{:itvl #xt/interval-ym "P12M"}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1-10' YEAR TO MONTH AS INTERVAL YEAR) as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT0S"]}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1 11:11:11.111' DAY TO SECOND AS INTERVAL DAY) as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT11H"]}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1 11:11:11.111' DAY TO SECOND AS INTERVAL DAY TO HOUR) as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT11H11M"]}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1 11:11:11.111' DAY TO SECOND AS INTERVAL DAY TO MINUTE) as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT11H11M11.111S"]}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1 11:11:11.111' DAY TO SECOND AS INTERVAL DAY TO SECOND) as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT11H11M11S"]}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1 11:11:11.111' DAY TO SECOND AS INTERVAL DAY TO SECOND(0)) as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT35H"]}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1 11:11:11.111' DAY TO SECOND AS INTERVAL HOUR) as itvl"))))

(t/deftest test-cast-int-to-interval
  (t/is (= [{:itvl #xt/interval-mdn ["P3D" "PT0S"]}]
           (xt/q tu/*node* "SELECT CAST(3 AS INTERVAL DAY) as itvl")))

  (t/is (= [{:itvl #xt/interval-ym "P24M"}]
           (xt/q tu/*node* "SELECT CAST(2 AS INTERVAL YEAR) as itvl")))

  (t/is (thrown-with-msg?
         IllegalArgumentException
         #"Cannot cast integer to a multi field interval"
         (xt/q tu/*node* "SELECT CAST(2 AS INTERVAL YEAR TO MONTH) as itvl"))))

(t/deftest test-cast-string-to-interval-with-qualifier
  (t/is (= [{:itvl #xt/interval-mdn ["P3D" "PT11H10M"]}]
           (xt/q tu/*node* "SELECT CAST('3 11:10' AS INTERVAL DAY TO MINUTE) as itvl")))

  (t/is (= [{:itvl #xt/interval-ym "P24M"}]
           (xt/q tu/*node* "SELECT CAST('2' AS INTERVAL YEAR) as itvl")))

  (t/is (= [{:itvl #xt/interval-ym "P22M"}]
           (xt/q tu/*node* "SELECT CAST('1-10' AS INTERVAL YEAR TO MONTH) as itvl")))

  (t/is (thrown-with-msg?
         IllegalArgumentException
         #"Interval end field must have less significance than the start field."
         (xt/q tu/*node* "SELECT CAST('11:10' AS INTERVAL MINUTE TO HOUR) as itvl"))))

(t/deftest test-cast-string-to-interval-without-qualifier
  (t/is (= [{:itvl #xt/interval-mdn ["P3D" "PT11H10M"]}]
           (xt/q tu/*node* "SELECT CAST('P3DT11H10M' AS INTERVAL) as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P24M" "PT0S"]}]
           (xt/q tu/*node* "SELECT CAST('P2Y' AS INTERVAL) as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P22M" "PT0S"]}]
           (xt/q tu/*node* "SELECT CAST('P1Y10M' AS INTERVAL) as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P1M1D" "PT1H1M1.11111S"]}]
           (xt/q tu/*node* "SELECT CAST('P1M1DT1H1M1.11111S' AS INTERVAL) as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT-1H-1M"]}]
           (xt/q tu/*node* "SELECT CAST('PT-1H-1M' AS INTERVAL) as itvl"))))

(t/deftest test-cast-interval-to-string
  (t/is (= [{:string "P2YT0S"}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '2' YEAR AS VARCHAR) as string")))

  (t/is (= [{:string "P22MT0S"}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1-10' YEAR TO MONTH AS VARCHAR) as string")))

  (t/is (= [{:string "P-22MT0S"}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '-1-10' YEAR TO MONTH AS VARCHAR) as string")))

  (t/is (= [{:string "P1DT0S"}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1' DAY AS VARCHAR) as string")))

  (t/is (= [{:string "P1DT10H10M10S"}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '1 10:10:10' DAY TO SECOND AS VARCHAR) as string")))

  (t/is (= [{:string "P0DT10M10.111111111S"}]
           (xt/q tu/*node* "SELECT CAST(INTERVAL '10:10.111111111' MINUTE TO SECOND(9) AS VARCHAR) as string"))))


(t/deftest test-timestamp-literal
  (t/are
   [sql expected]
   (= expected (plan-expr-with-foo sql))
    "TIMESTAMP '3000-03-15 20:40:31'" #xt.time/date-time "3000-03-15T20:40:31"
    "TIMESTAMP '3000-03-15 20:40:31.11'" #xt.time/date-time "3000-03-15T20:40:31.11"
    "TIMESTAMP '3000-03-15 20:40:31.2222'" #xt.time/date-time "3000-03-15T20:40:31.2222"
    "TIMESTAMP '3000-03-15 20:40:31.44444444'" #xt.time/date-time "3000-03-15T20:40:31.44444444"
    "TIMESTAMP '3000-03-15 20:40:31+03:44'" #xt.time/zoned-date-time "3000-03-15T20:40:31+03:44"
    "TIMESTAMP '3000-03-15 20:40:31.12345678+13:12'" #xt.time/zoned-date-time "3000-03-15T20:40:31.123456780+13:12"
    "TIMESTAMP '3000-03-15 20:40:31.12345678-14:00'" #xt.time/zoned-date-time"3000-03-15T20:40:31.123456780-14:00"
    "TIMESTAMP '3000-03-15 20:40:31.12345678+14:00'" #xt.time/zoned-date-time"3000-03-15T20:40:31.123456780+14:00"
    "TIMESTAMP '3000-03-15 20:40:31-11:44'" #xt.time/zoned-date-time "3000-03-15T20:40:31-11:44"
    "TIMESTAMP '3000-03-15T20:40:31-11:44'" #xt.time/zoned-date-time "3000-03-15T20:40:31-11:44"
    "TIMESTAMP '3000-03-15T20:40:31Z'" #xt.time/zoned-date-time "3000-03-15T20:40:31Z"

    "TIMESTAMP '3000-04-15T20:40:31+01:00[Europe/London]'" #xt.time/zoned-date-time "3000-04-15T20:40:31+01:00[Europe/London]"
    ;; corrects the offset to the zone's offset
    "TIMESTAMP '3000-04-15T20:40:31+05:00[Europe/London]'" #xt.time/zoned-date-time "3000-04-15T20:40:31+01:00[Europe/London]"
    ;; provides the correct offset for the zone
    "TIMESTAMP '3000-04-15T20:40:31[Europe/London]'" #xt.time/zoned-date-time "3000-04-15T20:40:31+01:00[Europe/London]"))

(t/deftest test-time-literal
  (t/are
   [sql expected]
   (= expected (plan-expr-with-foo sql))
    "TIME '20:40:31'" #xt.time/time "20:40:31"
    "TIME '20:40:31.467'" #xt.time/time "20:40:31.467"
    "TIME '20:40:31.932254'" #xt.time/time "20:40:31.932254"
    "TIME '20:40:31-03:44'" #xt.time/offset-time "20:40:31-03:44"
    "TIME '20:40:31+03:44'" #xt.time/offset-time "20:40:31+03:44"
    "TIME '20:40:31.467+14:00'" #xt.time/offset-time "20:40:31.467+14:00"))

(t/deftest date-literal
  (t/are
   [sql expected]
   (= expected (plan-expr-with-foo sql))
    "DATE '3000-03-15'" #xt.time/date "3000-03-15"))

(t/deftest interval-literal
  (t/are [sql expected] (= expected (plan-expr-with-foo sql))
    "INTERVAL 'P1Y'" #xt/interval-mdn ["P1Y" "PT0S"]
    "INTERVAL 'P1Y-2M3D'" #xt/interval-mdn ["P1Y-2M3D" "PT0S"]
    "INTERVAL 'PT5H6M12.912S'" #xt/interval-mdn ["P0D" "PT5H6M12.912S"]
    "INTERVAL 'PT5H-6M-12.912S'" #xt/interval-mdn ["P0D" "PT4H53M47.088S"]
    "INTERVAL 'P1Y3DT12H52S'" #xt/interval-mdn ["P1Y3D" "PT12H52S"]
    "INTERVAL 'P1Y10M3DT12H52S'" #xt/interval-mdn ["P1Y10M3D" "PT12H52S"]))

(t/deftest interval-literal-query
  (t/is (= [{:itvl #xt/interval-mdn ["P12M" "PT0S"]}]
           (xt/q tu/*node* "SELECT INTERVAL 'P1Y' as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P10M3D" "PT0S"]}]
           (xt/q tu/*node* "SELECT INTERVAL 'P1Y-2M3D' as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT5H6M12.912S"]}]
           (xt/q tu/*node* "SELECT INTERVAL 'PT5H6M12.912S' as itvl")))

  (t/is (= [{:itvl #xt/interval-mdn ["P22M3D" "PT4H53M47.088S"]}]
           (xt/q tu/*node* "SELECT INTERVAL 'P1Y10M3DT5H-6M-12.912S' as itvl"))))

(t/deftest duration-literal
  (t/are [sql expected] (= expected (plan-expr-with-foo sql))
    "DURATION 'P1D'" #xt.time/duration "PT24H"
    "DURATION 'PT1H'" #xt.time/duration "PT1H"
    "DURATION 'PT1M'" #xt.time/duration "PT1M"
    "DURATION 'PT1H1M1.111111S'" #xt.time/duration "PT1H1M1.111111S"
    "DURATION 'P1DT1H'" #xt.time/duration "PT25H"
    "DURATION 'P1DT10H1M1.111111S'" #xt.time/duration "PT34H1M1.111111S"
    "DURATION 'PT-1H'" #xt.time/duration "PT-1H"
    "DURATION 'P-1DT2H'" #xt.time/duration "PT-22H"
    "DURATION 'P-1DT-10H-1M'" #xt.time/duration "PT-34H-1M"))


(t/deftest duration-literal-query
  (t/is (= [{:duration #xt.time/duration "PT24H"}]
           (xt/q tu/*node* "SELECT DURATION 'P1D' as \"duration\"")))

  (t/is (= [{:duration #xt.time/duration "PT1H"}]
           (xt/q tu/*node* "SELECT DURATION 'PT1H' as \"duration\"")))

  (t/is (= [{:duration #xt.time/duration "PT26H"}]
           (xt/q tu/*node* "SELECT DURATION 'P1DT2H' as \"duration\"")))

  (t/is (= [{:duration #xt.time/duration "PT-22H"}]
           (xt/q tu/*node* "SELECT DURATION 'P-1DT2H' as \"duration\""))))

(t/deftest test-date-trunc-plan
  (t/testing "TIMESTAMP behaviour"
    (t/are
     [sql expected]
     (= expected (plan-expr-with-foo sql))
      "DATE_TRUNC(MICROSECOND, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "MICROSECOND" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(MILLISECOND, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "MILLISECOND" #xt.time/date-time "2021-10-21T12:34:56")
      "date_trunc(second, timestamp '2021-10-21T12:34:56')" '(date_trunc "SECOND" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(MINUTE, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "MINUTE" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(HOUR, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "HOUR" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(DAY, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "DAY" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(WEEK, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "WEEK" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(QUARTER, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "QUARTER" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(MONTH, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "MONTH" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(YEAR, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "YEAR" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(DECADE, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "DECADE" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(CENTURY, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "CENTURY" #xt.time/date-time "2021-10-21T12:34:56")
      "DATE_TRUNC(MILLENNIUM, TIMESTAMP '2021-10-21T12:34:56')" '(date_trunc "MILLENNIUM" #xt.time/date-time "2021-10-21T12:34:56")))

  (t/testing "INTERVAL behaviour"
    (t/are
     [sql expected]
     (= expected (plan-expr-with-foo sql))
      "DATE_TRUNC(DAY, INTERVAL '5' DAY)" '(date_trunc "DAY" (single-field-interval "5" "DAY" 2 6))
      "date_trunc(hour, interval '3 02:47:33' day to second)" '(date_trunc "HOUR" (multi-field-interval "3 02:47:33" "DAY" 2 "SECOND" 6)))))

(t/deftest test-datetime-functions-plan
  (t/are
   [sql expected]
   (= expected (plan-expr-with-foo sql))
    "CURRENT_DATE" '(current-date)
    "CURRENT_DATE()" '(current-date)
    "CURRENT_TIME" '(current-time)
    "CURRENT_TIME()" '(current-time)
    "CURRENT_TIME(6)" '(current-time 6)
    "CURRENT_TIMESTAMP" '(current-timestamp)
    "CURRENT_TIMESTAMP()" '(current-timestamp)
    "CURRENT_TIMESTAMP(6)" '(current-timestamp 6)
    "LOCALTIME" '(local-time)
    "LOCALTIME()" '(local-time)
    "LOCALTIME(6)" '(local-time 6)
    "LOCALTIMESTAMP" '(local-timestamp)
    "LOCALTIMESTAMP()" '(local-timestamp)
    "LOCALTIMESTAMP(6)" '(local-timestamp 6)
    "END_OF_TIME" 'xtdb/end-of-time
    "END_OF_TIME()" 'xtdb/end-of-time))

(t/deftest test-date-trunc-query
  (t/is (= [{:timestamp #xt.time/zoned-date-time "2021-10-21T12:34:00Z"}]
           (xt/q tu/*node* "SELECT DATE_TRUNC(MINUTE, TIMESTAMP '2021-10-21T12:34:56Z') as \"timestamp\"")))

  (t/is (= [{:timestamp #xt.time/zoned-date-time "2021-10-21T12:00:00Z"}]
           (xt/q tu/*node* "select date_trunc(hour, timestamp '2021-10-21T12:34:56Z') as \"timestamp\"")))

  (t/is (= [{:timestamp #xt.time/date "2001-01-01"}]
           (xt/q tu/*node* "select date_trunc(year, DATE '2001-11-27') as \"timestamp\"")))

  (t/is (= [{:timestamp #xt.time/date-time "2021-10-21T12:00:00"}]
           (xt/q tu/*node* "select date_trunc(hour, timestamp '2021-10-21T12:34:56') as \"timestamp\""))))

(t/deftest test-date-trunc-with-timezone-query
  (t/is (= [{:timestamp #xt.time/zoned-date-time "2001-02-16T08:00-05:00"}]
           (xt/q tu/*node* "select date_trunc(day, TIMESTAMP '2001-02-16 15:38:11-05:00', 'Australia/Sydney') as \"timestamp\"")))

  (t/is (thrown-with-msg?
         ZoneRulesException
         #"Unknown time-zone ID: NotRealRegion"
         (xt/q tu/*node* "select date_trunc(hour, TIMESTAMP '2000-01-02 00:43:11+00:00', 'NotRealRegion') as \"timestamp\""))))

(t/deftest test-date-trunc-with-interval-query
  (t/is (= [{:interval #xt/interval-mdn ["P36M" "PT0S"]}]
           (xt/q tu/*node* "SELECT DATE_TRUNC(YEAR, INTERVAL '3' YEAR + INTERVAL 'P3M') as \"interval\"")))

  (t/is (= [{:interval #xt/interval-mdn ["P3M4D" "PT2S"]}]
           (xt/q tu/*node* "SELECT DATE_TRUNC(SECOND, INTERVAL '3' MONTH + INTERVAL 'P4DT2S') as `interval`")))

  (t/is (= [{:interval #xt/interval-mdn ["P3M4D" "PT0S"]}]
           (xt/q tu/*node* "SELECT DATE_TRUNC(DAY, INTERVAL 'P3M' + INTERVAL '4' DAY + INTERVAL '2' SECOND) as \"interval\"")))

  (t/is (= [{:interval #xt/interval-mdn ["P3M" "PT0S"]}]
           (xt/q tu/*node* "SELECT DATE_TRUNC(MONTH, INTERVAL '3' MONTH + INTERVAL 'P4D' + INTERVAL '2' SECOND) as \"interval\""))))

(t/deftest test-extract-plan
  (t/testing "TIMESTAMP behaviour"
    (t/are
     [sql expected]
     (= expected (plan-expr-with-foo sql))
      "extract(second from timestamp '2021-10-21T12:34:56')" '(extract "SECOND" #xt.time/date-time "2021-10-21T12:34:56")
      "EXTRACT(MINUTE FROM TIMESTAMP '2021-10-21T12:34:56')" '(extract "MINUTE" #xt.time/date-time "2021-10-21T12:34:56")
      "EXTRACT(HOUR FROM TIMESTAMP '2021-10-21T12:34:56')" '(extract "HOUR" #xt.time/date-time "2021-10-21T12:34:56")
      "EXTRACT(DAY FROM TIMESTAMP '2021-10-21T12:34:56')" '(extract "DAY" #xt.time/date-time "2021-10-21T12:34:56")
      "EXTRACT(MONTH FROM TIMESTAMP '2021-10-21T12:34:56')" '(extract "MONTH" #xt.time/date-time "2021-10-21T12:34:56")
      "EXTRACT(YEAR FROM TIMESTAMP '2021-10-21T12:34:56')" '(extract "YEAR" #xt.time/date-time "2021-10-21T12:34:56")
      "EXTRACT(TIMEZONE_MINUTE FROM TIMESTAMP '2021-10-21T12:34:56')" '(extract "TIMEZONE_MINUTE" #xt.time/date-time "2021-10-21T12:34:56")
      "EXTRACT(TIMEZONE_HOUR FROM TIMESTAMP '2021-10-21T12:34:56')" '(extract "TIMEZONE_HOUR" #xt.time/date-time "2021-10-21T12:34:56")))

  (t/testing "INTERVAL behaviour"
    (t/are
     [sql expected]
     (= expected (plan-expr-with-foo sql))
      "EXTRACT(second from interval '3 02:47:33' day to second)" '(extract "SECOND" (multi-field-interval "3 02:47:33" "DAY" 2 "SECOND" 6))
      "EXTRACT(MINUTE FROM INTERVAL '5' DAY)" '(extract "MINUTE" (single-field-interval "5" "DAY" 2 6))))

  (t/testing "TIME behaviour"
    (t/are
     [sql expected]
     (= expected (plan-expr-with-foo sql))
      "EXTRACT(second from time '11:11:11')" '(extract "SECOND" #xt.time/time "11:11:11")
      "EXTRACT(MINUTE FROM TIME '11:11:11')" '(extract "MINUTE" #xt.time/time "11:11:11"))))


(t/deftest test-extract-query
  (t/testing "timestamp behavior"
    (t/is (= [{:x 34}]
             (xt/q tu/*node* "SELECT EXTRACT(MINUTE FROM TIMESTAMP '2021-10-21T12:34:56') as x")))

    (t/is (= [{:x 2021}]
             (xt/q tu/*node* "SELECT EXTRACT(YEAR FROM TIMESTAMP '2021-10-21T12:34:56') as x")))

    (t/is (thrown-with-msg?
           UnsupportedOperationException
           #"Extract \"TIMEZONE_HOUR\" not supported for type timestamp without timezone"
           (xt/q tu/*node* "SELECT EXTRACT(TIMEZONE_HOUR FROM TIMESTAMP '2021-10-21T12:34:56') as x"))))

  (t/testing "timestamp with timezone behavior"
    (t/is (= [{:x 34}]
             (xt/q tu/*node* "SELECT EXTRACT(MINUTE FROM TIMESTAMP '2021-10-21T12:34:56+05:00') as x")))

    (t/is (= [{:x 5}]
             (xt/q tu/*node* "SELECT EXTRACT(TIMEZONE_HOUR FROM TIMESTAMP '2021-10-21T12:34:56+05:00') as x"))))

  (t/testing "date behavior"
    (t/is (= [{:x 3}]
             (xt/q tu/*node* "SELECT EXTRACT(MONTH FROM DATE '2001-03-11') as x")))

    (t/is (thrown-with-msg?
           UnsupportedOperationException
           #"Extract \"TIMEZONE_HOUR\" not supported for type date"
           (xt/q tu/*node* "SELECT EXTRACT(TIMEZONE_HOUR FROM DATE '2001-03-11') as x"))))

  (t/testing "time behavior"
    (t/is (= [{:x 34}]
             (xt/q tu/*node* "SELECT EXTRACT(MINUTE FROM TIME '12:34:56') as x")))

    (t/is (= [{:x 12}]
             (xt/q tu/*node* "SELECT EXTRACT(HOUR FROM TIME '12:34:56') as x")))

    (t/is (thrown-with-msg?
           UnsupportedOperationException
           #"Extract \"TIMEZONE_HOUR\" not supported for type timestamp without timezone"
           (xt/q tu/*node* "SELECT EXTRACT(TIMEZONE_HOUR FROM TIMESTAMP '2021-10-21T12:34:56') as x"))))

  (t/testing "interval behavior"
    (t/is (= [{:x 3}]
             (xt/q tu/*node* "SELECT EXTRACT(DAY FROM INTERVAL '3 02:47:33' DAY TO SECOND) as x")))

    (t/is (= [{:x 47}]
             (xt/q tu/*node* "SELECT EXTRACT(MINUTE FROM INTERVAL '3 02:47:33' DAY TO SECOND) as x")))

    (t/is (thrown-with-msg?
           UnsupportedOperationException
           #"Extract \"TIMEZONE_HOUR\" not supported for type interval"
           (xt/q tu/*node* "SELECT EXTRACT(TIMEZONE_HOUR FROM INTERVAL '3 02:47:33' DAY TO SECOND) as x")))))

(t/deftest test-age-function
  (t/testing "testing AGE with timestamps"
    (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT2H"]}]
             (xt/q tu/*node* "SELECT AGE(TIMESTAMP '2022-05-02T01:00:00', TIMESTAMP '2022-05-01T23:00:00') as itvl")))
    (t/is (= [{:itvl #xt/interval-mdn ["P6M" "PT0S"]}]
             (xt/q tu/*node* "SELECT AGE(TIMESTAMP '2022-11-01T00:00:00', TIMESTAMP '2022-05-01T00:00:00') as itvl")))
    (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT1H"]}]
             (xt/q tu/*node* "SELECT AGE(TIMESTAMP '2023-01-01T01:00:00', TIMESTAMP '2023-01-01T00:00:00') as itvl"))))

  (t/testing "testing AGE with timestamp with timezone"
    (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT1H"]}]
             (xt/q tu/*node* "SELECT AGE(TIMESTAMP '2023-06-01T11:00:00+01:00[Europe/London]', TIMESTAMP '2023-06-01T11:00:00+02:00[Europe/Berlin]') as itvl")))
    (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT2H"]}]
             (xt/q tu/*node* "SELECT AGE(TIMESTAMP '2023-06-01T09:00:00-05:00[America/Chicago]', TIMESTAMP '2023-06-01T12:00:00') as itvl"))))

  (t/testing "testing AGE with date"
    (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT0S"]}]
             (xt/q tu/*node* "SELECT AGE(DATE '2023-01-02', DATE '2023-01-01') as itvl")))
    (t/is (= [{:itvl #xt/interval-mdn ["P-12M" "PT0S"]}]
             (xt/q tu/*node* "SELECT AGE(DATE '2023-01-01', DATE '2024-01-01') as itvl"))))

  (t/testing "test with mixed types"
    (t/is (= [{:itvl #xt/interval-mdn ["P1D" "PT0S"]}]
             (xt/q tu/*node* "SELECT AGE(DATE '2023-01-02', TIMESTAMP '2023-01-01T00:00:00') as itvl")))
    (t/is (= [{:itvl #xt/interval-mdn ["P-6M" "PT0S"]}]
             (xt/q tu/*node* "SELECT AGE(TIMESTAMP '2022-05-01T00:00:00', TIMESTAMP '2022-11-01T00:00:00+00:00[Europe/London]') as itvl")))
    (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT2H0.001S"]}]
             (xt/q tu/*node* "SELECT AGE(TIMESTAMP '2023-07-01T12:00:30.501', TIMESTAMP '2023-07-01T12:00:30.500+02:00[Europe/Berlin]') as itvl")))
    (t/is (= [{:itvl #xt/interval-mdn ["P0D" "PT-2H-0.001S"]}]
             (xt/q tu/*node* "SELECT AGE(TIMESTAMP '2023-07-01T12:00:30.499+02:00[Europe/Berlin]', TIMESTAMP '2023-07-01T12:00:30.500') as itvl")))))


(t/deftest test-period-predicates
  (t/are [expected sql] (= expected (plan-expr-with-foo sql))
    '(and (<= f/xt$valid_from #xt.time/zoned-date-time "2000-01-01T00:00Z")
          (>= f/xt$valid_to #xt.time/zoned-date-time "2001-01-01T00:00Z"))
    "foo.VALID_TIME CONTAINS PERIOD (TIMESTAMP '2000-01-01 00:00:00+00:00', TIMESTAMP '2001-01-01 00:00:00+00:00')"

    '(and (<= f/xt$valid_from #xt.time/zoned-date-time "2000-01-01T00:00Z")
          (>= f/xt$valid_to #xt.time/zoned-date-time "2000-01-01T00:00Z"))
    "foo.VALID_TIME CONTAINS TIMESTAMP '2000-01-01 00:00:00+00:00'"

    ;; also testing all period-predicate permutations
    '(and (< f/xt$valid_from #xt.time/zoned-date-time "2001-01-01T00:00Z")
          (> f/xt$valid_to #xt.time/zoned-date-time "2000-01-01T00:00Z"))
    "foo.VALID_TIME OVERLAPS PERIOD (TIMESTAMP '2000-01-01 00:00:00+00:00', TIMESTAMP '2001-01-01 00:00:00+00:00')"

    '(and (< f/xt$valid_from f/xt$valid_to) (> f/xt$valid_to f/xt$valid_from))
    "foo.VALID_TIME OVERLAPS foo.VALID_TIME"

    '(and (< #xt.time/zoned-date-time "2000-01-01T00:00Z" #xt.time/zoned-date-time "2003-01-01T00:00Z")
          (> #xt.time/zoned-date-time "2001-01-01T00:00Z" #xt.time/zoned-date-time "2002-01-01T00:00Z"))
    "PERIOD (TIMESTAMP '2000-01-01 00:00:00+00:00', TIMESTAMP '2001-01-01 00:00:00+00:00')
    OVERLAPS PERIOD (TIMESTAMP '2002-01-01 00:00:00+00:00', TIMESTAMP '2003-01-01 00:00:00+00:00')"

    '(and (= f/xt$system_from #xt.time/zoned-date-time "2000-01-01T00:00Z")
          (= f/xt$system_to #xt.time/zoned-date-time "2001-01-01T00:00Z"))
    "foo.SYSTEM_TIME EQUALS PERIOD (TIMESTAMP '2000-01-01 00:00:00+00:00', TIMESTAMP '2001-01-01 00:00:00+00:00')"

    '(<= f/xt$valid_to #xt.time/zoned-date-time "2000-01-01T00:00Z")
    "foo.VALID_TIME PRECEDES PERIOD (TIMESTAMP '2000-01-01 00:00:00+00:00', TIMESTAMP '2001-01-01 00:00:00+00:00')"

    '(>= f/xt$system_from #xt.time/zoned-date-time "2001-01-01T00:00Z")
    "foo.SYSTEM_TIME SUCCEEDS PERIOD (TIMESTAMP '2000-01-01 00:00:00+00:00', TIMESTAMP '2001-01-01 00:00:00+00:00')"

    '(= f/xt$valid_to #xt.time/zoned-date-time "2000-01-01T00:00Z")
    "foo.VALID_TIME IMMEDIATELY PRECEDES PERIOD (TIMESTAMP '2000-01-01 00:00:00+00:00', TIMESTAMP '2001-01-01 00:00:00+00:00')"

    '(= f/xt$valid_from #xt.time/zoned-date-time "2001-01-01T00:00Z")
    "foo.VALID_TIME IMMEDIATELY SUCCEEDS PERIOD (TIMESTAMP '2000-01-01 00:00:00+00:00', TIMESTAMP '2001-01-01 00:00:00+00:00')"))

(t/deftest test-period-predicates-point-in-time
  (t/are [expected sql] (= expected (plan-expr-with-foo sql))

    '(and (<= f/xt$valid_from f/a) (>= f/xt$valid_to f/a))
    "foo.valid_time CONTAINS foo.a"

    '(and
      (<= f/xt$valid_from #xt.time/zoned-date-time "2010-01-01T11:10:11Z")
      (>= f/xt$valid_to #xt.time/zoned-date-time "2010-01-01T11:10:11Z"))
    "foo.valid_time CONTAINS TIMESTAMP '2010-01-01T11:10:11Z'"

    '(and (<= f/xt$valid_from f/a) (>= f/xt$valid_to f/a))
    "foo.valid_time CONTAINS PERIOD(foo.a, foo.a)"

    '(and (<= f/xt$valid_from f/xt$system_from) (>= f/xt$valid_to f/xt$system_from))
    "foo.valid_time CONTAINS foo.xt$system_from"))


(t/deftest test-coalesce
  (t/testing "planning"
    (t/are [expected sql] (= expected (plan-expr-with-foo sql))
      '(coalesce 1 2) "COALESCE(1,2)"
      '(coalesce 1 2 3 4) "COALESCE(1,2,3,4)"
      '(coalesce f/a f/b) "COALESCE(foo.a,foo.b)"
      '(coalesce f/a 2) "COALESCE(foo.a,2)"
      '(coalesce nil nil 2) "COALESCE(NULL,NULL,2)"))

  (t/testing "running"
    (xt/submit-tx tu/*node* [[:put-docs :docs {:xt/id 1 :x 3}]])

    (t/are [expected sql] (= expected (xt/q tu/*node* sql))
      [{:xt/column-1 1}] "SELECT COALESCE(1)"
      [{:xt/column-1 2}] "SELECT COALESCE(2,1)"
      [{:xt/column-1 3}] "SELECT COALESCE(NULL, NULL, 3)"
      [{:xt/column-1 3}] "SELECT COALESCE(NULL,docs.x) FROM docs")))

(t/deftest test-nullif
  (t/testing "planning"
    (t/are [expected sql] (= expected (plan-expr-with-foo sql))
      '(nullif 1 2) "NULLIF(1,2)"
      '(nullif 2 2) "NULLIF(2,2)"
      '(nullif f/a f/b) "NULLIF(foo.a,foo.b)"
      '(nullif f/a 2) "NULLIF(foo.a,2)"))

  (t/testing "running"
    (xt/submit-tx tu/*node* [[:put-docs :docs {:xt/id 1 :x 3 :y 4 :z 3}]])

    (t/are [expected sql] (= expected (xt/q tu/*node* sql))
      [{:xt/column-1 1}] "SELECT NULLIF(1,2)"
      [{:xt/column-1 2}] "SELECT NULLIF(2,1)"
      [{}] "SELECT NULLIF(2,2)"
      [{}] "SELECT NULLIF(NULL, NULL)"
      [{}] "SELECT NULLIF(docs.x,docs.z) FROM docs"
      [{:xt/column-1 3}] "SELECT NULLIF(docs.x,docs.y) FROM docs"
      [{:xt/column-1 3}] "SELECT NULLIF(docs.x, NULL) FROM docs"
      [{}] "SELECT NULLIF(NULL, docs.x) FROM docs")))

(t/deftest test-period-predicates-point-in-time-errors
  (t/is (thrown-with-msg?
         IllegalArgumentException
         #"line 1:66 no viable alternative at input"
         (sql-test/plan-sql "SELECT f.foo FROM foo f WHERE f.valid_time OVERLAPS f.other_column"
                            {:table-info {"foo" #{"foo" "other_column"}}})))

  (t/is (thrown-with-msg?
         IllegalArgumentException
         #"line 1:52 no viable alternative at input"
         (sql-test/plan-sql
          "SELECT f.foo FROM foo f WHERE f.valid_time OVERLAPS TIMESTAMP '2010-01-01T11:10:11Z'"
          {:table-info {"foo" #{"foo"}}}))))

(t/deftest test-min-long-value-275
  (t/is (= Long/MIN_VALUE (plan/plan-expr "-9223372036854775808"))))

(t/deftest test-postgres-session-variables
  ;; These currently return hard-coded values.
  (t/is (= [{:xt/column-1 "xtdb"}]
           (xt/q tu/*node* "SELECT current_user")))

  (t/is (= [{:xt/column-1 "xtdb"}]
           (xt/q tu/*node* "SELECT current_database")))

  (t/is (= [{:xt/column-1 "public"}]
           (xt/q tu/*node* "SELECT current_schema"))))

(t/deftest test-postgres-access-control-functions
  ;; These current functions should always should return true
  (t/are [sql expected] (= expected (plan/plan-expr sql))
    "has_table_privilege('xtdb','docs', 'select')" true
    "has_table_privilege('docs', 'select')" true
    "pg_catalog.has_table_privilege('docs', 'select')" true

    "has_schema_privilege('xtdb', 'public', 'select')" true
    "has_schema_privilege('public', 'select')" true
    "pg_catalog.has_schema_privilege('public', 'select')" true

    "has_table_privilege(current_user, 'docs', 'select')" true
    "has_schema_privilege(current_user, 'public', 'select')" true)

  (t/testing "example SQL query"
    (xt/submit-tx tu/*node* [[:put-docs :docs {:xt/id 1 :x 3}]])

    (t/is (= [{:x 3}]
             (xt/q tu/*node* "SELECT docs.x FROM docs WHERE has_table_privilege('docs', 'select') ")))))

;; TODO: Add this?
#_(t/deftest test-random-fn
  (t/is (= true (-> (xt/q tu/*node* "SELECT 0.0 <= random() AS greater") first :greater)))
  (t/is (= true (-> (xt/q tu/*node* "SELECT random() < 1.0 AS smaller ") first :smaller))))

(t/deftest test-arithmetic-precedence-slt
  (t/is (= [{:col2 -102}]
           (xt/q tu/*node* "SELECT - 99 / 7 * 14 + + 94 AS col2")))

  (t/is (= [{:col2 45}]
           (xt/q tu/*node* "SELECT - 99 * 7 / 14 + + 94 AS col2")))

  (t/is (= [{:col2 5}]
           (xt/q tu/*node* "SELECT - 53 / + 86 * - + 44 - 66 + + + 71 AS col2")))

  (t/is (= [{:col2 -8}]
           (xt/q tu/*node* "SELECT ALL - - 72 - 27 + + ( - 53 ) col2"))))

(t/deftest test-uuid-literal
  (t/testing "Planning Success"
    (t/is (= #uuid "550e8400-e29b-41d4-a716-446655440000"
             (plan-expr-with-foo "UUID '550e8400-e29b-41d4-a716-446655440000'"))))

  (t/testing "Planning Error"
    (t/is (thrown-with-msg?
           IllegalArgumentException
           #"Cannot parse UUID: error"
           (plan-expr-with-foo "UUID 'error'")))) 

  (t/testing "Running"
    (t/is (= [{:uuid-literal #uuid "550e8400-e29b-41d4-a716-446655440000"}]
             (xt/q tu/*node* "SELECT UUID '550e8400-e29b-41d4-a716-446655440000' AS uuid_literal")))))