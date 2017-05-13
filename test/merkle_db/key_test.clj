(ns merkle-db.key-test
  (:require
    [clojure.future :refer [bytes?]]
    [clojure.test :refer :all]
    [clojure.test.check.generators :as gen]
    [com.gfredericks.test.chuck.clojure-test :refer [checking]]
    [merkle-db.key :as key]
    [merkle-db.test-utils]))


(deftest key-predicate
  (is (false? (key/bytes? nil)))
  (is (false? (key/bytes? :foo)))
  (is (false? (key/bytes? (byte-array [0 1 2]))))
  (is (true? (key/bytes? (key/create [0]))))
  (is (true? (key/bytes? (key/create [0 1 2 3 4])))))


(deftest lexicographic-ordering
  (testing "equal arrays"
    (is (zero? (key/compare (key/create []) (key/create []))))
    (is (zero? (key/compare (key/create [1 2 3]) (key/create [1 2 3]))))
    (is (false? (key/before? (key/create [1]) (key/create [1]))))
    (is (false? (key/after? (key/create [1]) (key/create [1])))))
  (testing "equal prefixes"
    (is (neg? (key/compare (key/create [1 2 3])
                           (key/create [1 2 3 4]))))
    (is (pos? (key/compare (key/create [1 2 3 4])
                           (key/create [1 2 3])))))
  (testing "order-before"
    (is (neg? (key/compare (key/create [1 2 3])
                           (key/create [1 2 4]))))
    (is (neg? (key/compare (key/create [1 2 3])
                           (key/create [1 3 2]))))
    (is (neg? (key/compare (key/create [0 2 3 4])
                           (key/create [1 3 2 1]))))
    (is (true? (key/before? (key/create [0 1])
                            (key/create [0 2])))))
  (testing "order-after"
    (is (pos? (key/compare (key/create [1 2 4])
                           (key/create [1 2 3]))))
    (is (pos? (key/compare (key/create [1 3 2])
                           (key/create [1 2 3]))))
    (is (pos? (key/compare (key/create [1 3 2 1])
                           (key/create [0 2 3 4]))))
    (is (true? (key/after? (key/create [0 1 2])
                           (key/create [0 1 0]))))))


(deftest min-max-util
  (let [a (key/create [0])
        b (key/create [5])
        c (key/create [7])
        d (key/create [8])]
    (testing "minimum keys"
      (is (= a (key/min a)))
      (is (= b (key/min b c)))
      (is (= b (key/min c d b)))
      (is (= a (key/min c d a b))))
    (testing "maximum keys"
      (is (= a (key/max a)))
      (is (= c (key/max b c)))
      (is (= d (key/max c d b)))
      (is (= d (key/max c d a b))))))


(defn- check-lexicoder
  ([generator]
   (check-lexicoder generator compare))
  ([generator cmp]
   (checking "reflexive coding" 50
     [[coder arg-gen] generator
      x arg-gen]
     (let [decoded (key/decode coder (key/encode coder x))]
       (if (bytes? x)
         (is (zero? (key/compare x decoded)))
         (is (= x decoded)))))
   (checking "sort order" 100
     [[coder arg-gen] generator
      a arg-gen
      b arg-gen]
     (let [rrank (cmp a b)
           ka (key/encode coder a)
           kb (key/encode coder b)]
       (cond
         (zero? rrank)
           (is (zero? (key/compare ka kb)))
         (pos? rrank)
           (is (pos? (key/compare ka kb)))
         :else
           (is (neg? (key/compare ka kb))))))))


(defn- lexi-comparator
  "Wrap a comparator for elements of type x to build a lexical comparator
  over sequences of xs."
  [cmp]
  (fn lex-cmp
    [as bs]
    (let [prefix-len (min (count as) (count bs))]
      (loop [as as, bs bs]
        (if (and (seq as) (seq bs))
          (let [rrank (cmp (first as) (first bs))]
            (if (zero? rrank)
              (recur (rest as) (rest bs))
              rrank))
          (- (count as) (count bs)))))))


(def lexicoder-generators
  "Map of lexicoder types to tuples of a lexicoder instance and a generator for
  values matching that lexicoder."
  {:string
   [key/string-lexicoder
    (gen/such-that not-empty gen/string)]

   :long
   [key/long-lexicoder
    gen/large-integer]

   :double
   [key/double-lexicoder
    (gen/double* {:NaN? false})]

   :instant
   [key/instant-lexicoder
    (gen/fmap #(java.time.Instant/ofEpochMilli %) gen/large-integer)]})


(deftest lexicoder-configs
  (is (thrown? Exception (key/lexicoder "not a keyword or vector")))
  (is (thrown? Exception (key/lexicoder [123 "needs a keyword first"]))))


(deftest bytes-lexicoder
  (is (identical? key/bytes-lexicoder (key/lexicoder :bytes)))
  (is (satisfies? key/Lexicoder key/bytes-lexicoder))
  (is (= :bytes (key/lexicoder-config key/bytes-lexicoder)))
  (is (thrown? Exception
        (key/lexicoder [:bytes :foo]))
      "should not accept any config parameters")
  (is (thrown? IllegalArgumentException
        (key/encode key/bytes-lexicoder (byte-array 0)))
      "should not encode empty bytes")
  (is (thrown? IllegalArgumentException
        (key/decode key/bytes-lexicoder (byte-array 0)))
      "should not decode empty bytes"))


(deftest ^:generative bytes-lexicoding
  (check-lexicoder
    (gen/return [key/bytes-lexicoder
                 (gen/such-that not-empty gen/bytes)])
    key/compare))


(deftest string-lexicoder
  (is (identical? key/string-lexicoder (key/lexicoder :string)))
  (is (satisfies? key/Lexicoder (key/lexicoder [:string "UTF-8"])))
  (is (= :string (key/lexicoder-config key/string-lexicoder)))
  (is (= [:string "US-ASCII"] (key/lexicoder-config (key/string-lexicoder* "US-ASCII"))))
  (is (thrown? Exception
        (key/lexicoder [:string "UTF-8" :bar]))
      "should only accept one config parameter")
  (is (thrown? IllegalArgumentException
        (key/encode key/string-lexicoder ""))
      "should not encode empty strings")
  (is (thrown? IllegalArgumentException
        (key/decode key/string-lexicoder (byte-array 0)))
      "should not decode empty bytes"))


(deftest ^:generative string-lexicoding
  (check-lexicoder
    (gen/return (:string lexicoder-generators)) ))


(deftest long-lexicoder
  (is (identical? key/long-lexicoder (key/lexicoder :long)))
  (is (= :long (key/lexicoder-config key/long-lexicoder)))
  (is (thrown? Exception
        (key/lexicoder [:long :bar]))
      "should not accept any config parameters")
  (is (thrown? IllegalArgumentException
        (key/decode key/long-lexicoder (byte-array 7)))
      "should require 8 bytes"))


(deftest ^:generative long-lexicoding
  (check-lexicoder
    (gen/return (:long lexicoder-generators)) ))


(deftest double-lexicoder
  (is (identical? key/double-lexicoder (key/lexicoder :double)))
  (is (= :double (key/lexicoder-config key/double-lexicoder)))
  (is (thrown? Exception
        (key/lexicoder [:double :bar]))
      "should not accept any config parameters"))


(deftest ^:generative double-lexicoding
  (check-lexicoder
    (gen/return (:double lexicoder-generators)) ))


(deftest instant-lexicoder
  (is (identical? key/instant-lexicoder (key/lexicoder :instant)))
  (is (= :instant (key/lexicoder-config key/instant-lexicoder)))
  (is (thrown? Exception
        (key/lexicoder [:instant :bar]))
      "should not accept any config parameters")
  (is (thrown? IllegalArgumentException
        (key/encode key/instant-lexicoder ""))
      "should not encode non-instant value"))


(deftest ^:generative instant-lexicoding
  (check-lexicoder
    (gen/return (:instant lexicoder-generators)) ))


(deftest sequence-lexicoder
  (is (satisfies? key/Lexicoder (key/lexicoder [:seq :long])))
  (is (= [:seq :long] (key/lexicoder-config (key/sequence-lexicoder key/long-lexicoder))))
  (is (thrown? Exception
        (key/lexicoder :seq))
      "should require at least one config parameter")
  (is (thrown? Exception
        (key/lexicoder [:seq :string :foo]))
      "should only accept one config parameter"))


(deftest ^:generative sequence-lexicoding
  (check-lexicoder
    (gen/fmap
      (fn [[coder arg-gen]]
        [(key/sequence-lexicoder coder)
         (gen/vector arg-gen)])
      (gen/elements (vals lexicoder-generators)))
    (lexi-comparator compare)))


(deftest tuple-lexicoder
  (is (satisfies? key/Lexicoder (key/lexicoder [:tuple :string])))
  (is (= [:tuple :long :string] (key/lexicoder-config (key/tuple-lexicoder
                                                        key/long-lexicoder
                                                        key/string-lexicoder))))
  (is (thrown? Exception
        (key/lexicoder [:tuple]))
      "should require at least one config parameter")
  (is (thrown? IllegalArgumentException
        (key/encode (key/tuple-lexicoder key/long-lexicoder) [0 0]))
      "should not encode tuples larger than coders")
  (is (thrown? IllegalArgumentException
        (key/encode (key/lexicoder [:tuple :long :string])
                    [0]))
      "should not encode tuples smaller than coders")
  (is (thrown? IllegalArgumentException
        (key/decode (key/tuple-lexicoder key/string-lexicoder)
                    (key/encode (key/tuple-lexicoder key/string-lexicoder
                                                     key/long-lexicoder)
                                ["foo" 123])))
      "should not decode tuple longer than coders"))


(deftest ^:generative tuple-lexicoding
  (check-lexicoder
    (gen/fmap
      (fn [generators]
        [(apply key/tuple-lexicoder (map first generators))
         (apply gen/tuple (map second generators))])
      (gen/not-empty (gen/vector (gen/elements (vals lexicoder-generators)))))))


(deftest reverse-lexicoder
  (is (satisfies? key/Lexicoder (key/lexicoder [:reverse :instant])))
  (is (= [:reverse :bytes] (key/lexicoder-config (key/reverse-lexicoder key/bytes-lexicoder))))
  (is (thrown? Exception
        (key/lexicoder [:reverse])))
  (is (thrown? Exception
        (key/lexicoder [:reverse :long :string]))))


(deftest ^:generative reverse-lexicoding
  (check-lexicoder
    (gen/fmap
      (fn [[coder arg-gen]]
        [(key/reverse-lexicoder coder) arg-gen])
      (gen/elements (vals lexicoder-generators)))
    (comp - compare)))
