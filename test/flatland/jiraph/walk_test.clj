(ns flatland.jiraph.walk-test
  (:use clojure.test
        flatland.jiraph.core
        [flatland.jiraph.walk :only [defwalk path paths *parallel-follow* intersection]]
        [flatland.useful.utils :only [adjoin]]
        [flatland.jiraph.walk.predicates :only [at-limit]])
  (:require [flatland.jiraph.layer.stm :as stm]
            [flatland.jiraph.layer.masai :as masai]
            [flatland.jiraph.layer.masai-sorted :as sorted])
  (:import (flatland.jiraph Test$Node)))

(defn test-graph []
  {:foo (masai/make-temp)
   :bar (masai/make-temp)
   :baz (sorted/make-temp :layout-fn (-> (constantly [{:pattern [:edges :*]}
                                                      {:pattern []}])
                                         (sorted/wrap-default-formats)
                                         (sorted/wrap-revisioned)))
   :stm (stm/make)})

(defwalk full-walk
  :add?          true
  :follow-layers (fn [& args] (flatland.jiraph.core/layers))
  :traverse?     true)

(deftest simple-walk
  (doseq [parallel? [true false]]
    (binding [*parallel-follow* parallel?]
      (with-graph (test-graph)
        (is (= ["1"] (:ids (full-walk "1"))))

        (update-node! :foo "1" adjoin {:edges {"2" {:a "foo"} "3" {:a "bar"}}})
        (update-node! :bar "2" adjoin {:edges {"3" {:a "foo"} "4" {:a "bar"}}})
        (update-node! :baz "2" adjoin {:edges {"5" {:a "foo"} "6" {:a "bar"}}})
        (update-node! :baz "4" adjoin {:edges {"7" {:a "foo"} "8" {:a "bar"}}})
        (update-node! :baz "8" adjoin {:edges {"8" {:a "foo"} "9" {:a "bar"}}})
        (update-node! :stm "9" adjoin {:edges {"2" {}}})

        (let [walk (full-walk "1")]
          (is (= 9 (:result-count walk)))
          (is (= ["1" "2" "3" "4" "5" "6" "7" "8" "9"] (:ids walk)))
          (is (= ["1" "2" "4" "8"] (map :id (path walk "8"))))
          (is (= ["1" "2" "4" "7"] (map :id (path walk "7"))))
          (is (= ["1" "2" "6"]     (map :id (path walk "6"))))
          (is (= ["1" "2"]         (map :id (path walk "2"))))
          (is (= [["1" "2"] ["1" "2" "4" "8" "9" "2"]] (map (partial map :id) (paths walk "2"))))
          (is (= [["1" "3"]             ["1" "2" "3"]] (map (partial map :id) (paths walk "3")))))

        (testing "early termination"
          (let [walk (full-walk "1" :terminate? (at-limit 5))]
            (is (= 5 (:result-count walk)))
            (is (= ["1" "2" "3" "4" "5"] (:ids walk)))
            (is (= nil (path walk "8")))
            (is (= nil (path walk "7")))
            (is (= nil (path walk "6")))
            (is (= ["1" "2"]   (map :id (path walk "2"))))
            (is (= [["1" "2"]] (map (partial map :id) (paths walk "2"))))
            (is (= [["1"]]     (map (partial map :id) (paths walk "1"))))))

        (testing "intersection"
          (is (= nil
                 (intersection (full-walk "1" :terminate? (at-limit 2))
                               (full-walk "8" :terminate? (at-limit 2)))))
          (is (= '("2")
                 (intersection (full-walk "1" :terminate? (at-limit 3))
                               (full-walk "8" :terminate? (at-limit 3)))))
          (is (= '("2" "3")
                 (intersection (full-walk "1" :terminate? (at-limit 4))
                               (full-walk "8" :terminate? (at-limit 4))))))

        (update-node! :foo "1" adjoin {:edges {"8" {:a "one"}}})
        (let [walk (full-walk "1")]
          (is (= 9 (:result-count walk)))
          (is (= ["1" "2" "3" "8" "4" "5" "6" "9" "7"] (:ids walk)))
          (is (= ["1" "8"] (map :id (path walk "8")))))))))
