(ns slam.hound.regrow-test
  (:require [clojure.test :refer [deftest is testing]]
            [slam.hound.regrow :refer [candidates disambiguate grow-ns-map
                                       regrow]])
  (:refer-clojure :exclude [/]))

(def sample-body
  '((set/union #{:a} #{:b})
    (string/join ["a" "b"]) ; Try to conflict with set/join
    (UUID/randomUUID)
    (instance? Compiler$BodyExpr nil)
    (io/copy (ByteArrayInputStream. (.getBytes "remotely human"))
             (doto (File. "/tmp/remotely-human") .deleteOnExit))
    (deftest ^:unit test-ns-to-map
      (is (= (ns-from-map {:ns 'slam.hound}))))))

;; Classes and vars for testing
(defrecord RegrowTestRecord [])
(defrecord UUID [])
(def +i-must-be-a-cl-user+ true)
(def -+_$?!*><='' :horribly-named-var)
(def / :special-case-token)
(def CapitalVar true)
(def Pattern "Not java.util.Pattern")
(def trim (constantly "Conflicts with clojure.string/trim"))

;; Explicitly require korma for tests below
(require 'korma.core)

(deftest ^:unit test-candidates
  (testing "finds static and dynamically created Java packages"
    (is (= (candidates :import 'UUID '((UUID/randomUUID)))
           '#{java.util.UUID slam.hound.regrow_test.UUID}))
    (is (= (candidates :import 'Compiler$BodyExpr '(Compiler$BodyExpr))
           '#{clojure.lang.Compiler$BodyExpr}))
    (is (= (candidates :import 'RegrowTestRecord '((RegrowTestRecord.)))
           '#{slam.hound.regrow_test.RegrowTestRecord})))
  (testing "finds aliased namespaces"
    (is (= (candidates :alias 's '((s/join #{:a} #{:b})))
           '#{clojure.set clojure.string korma.core})))
  (testing "finds referred vars"
    (is (= (candidates :refer 'join '((join #{:a} #{:b})))
           '#{clojure.set clojure.string korma.core}))))

(deftest test-alias-distance
  (let [d #'slam.hound.regrow/alias-distance
        max? (partial = Long/MAX_VALUE)]
    (is (max? (d "zbc" "abcdef")))
    (is (max? (d "azc" "abcdef")))
    (is (max? (d "abz" "abcdef")))
    (is (max? (d "abcd" "abc")))
    (is (= (d "a" "abcdef") 0))
    (is (= (d "abc" "abcdef") 0))
    (is (= (d "ace" "abcdef") 2))
    (is (= (d "fbb" "foo-bar-baz") 6))))

(deftest ^:unit test-disambiguate
  (testing "removes namespace matching :name in old-ns-map"
    (is (= (disambiguate '#{foo bar} :alias 'foo '{:old-ns-map {:name foo}})
           '[:alias bar])))
  (testing "removes cljs.core from candidates"
    (is (nil? (disambiguate '#{cljs.core} :refer 'defn {}))))
  (testing "removes candidates matching disambiguator-blacklist"
    (is (nil? (disambiguate '#{swank lancet} :alias 'swank {}))))
  (testing "removes namespaces with excluded vars"
    (is (nil? (disambiguate '#{clojure.string clojure.set}
                            :refer 'join
                            '{:old-ns-map
                              {:exclude {clojure.string #{join}
                                         clojure.set #{join}}}})))
    (is (nil? (disambiguate '#{clojure.core core.logic} :refer '==
                            '{:old-ns-map
                              {:xrefer #{clojure.core core.logic}
                               :refer {clojure.core #{} core.logic #{}}}}))))
  (testing "enforces one alias per namespace"
    (is (nil? (disambiguate '#{clojure.string}
                            :alias 's
                            '{:new-ns-map {:alias {clojure.string string}}}))))
  (testing "prefers imports from old ns"
    (is (= (disambiguate '#{java.util.UUID slam.hound.regrow_test.UUID}
                         :import 'UUID
                         '{:old-ns-map
                           {:import #{slam.hound.regrow_test.UUID}}})
           '[:import slam.hound.regrow_test.UUID])))
  (testing "prefers aliases from old ns"
    (is (= (disambiguate '#{a zzz} :alias 'x
                         '{:old-ns-map {:alias {zzz x}}})
           '[:alias zzz])))
  (testing "prefers refers from old ns"
    (is (= (disambiguate '#{a zzz} :refer 'x
                         '{:old-ns-map {:refer {zzz #{x}}}})
           '[:refer zzz])))
  (testing "prefers explicit refers over mass refers from old ns"
    (is (= (disambiguate '#{clojure.set clojure.string}
                         :refer 'join
                         '{:old-ns-map
                           {:refer-all #{clojure.set}
                            :refer {clojure.string #{join}}}})
           '[:refer clojure.string])))
  (testing "prefers aliases where the last segment matches"
    (is (= (disambiguate '#{clojure.set clojure.string} :alias 'string {})
           '[:alias clojure.string])))
  (testing "prefers candidates in project namespaces"
    (is (= (disambiguate
             '#{clojure.string slam.hound.regrow-test} :refer 'trim {})
           '[:refer slam.hound.regrow-test])))
  (testing "prefers candidates whose initials match the alias"
    (is (= (disambiguate '#{xray.yankee.zulu abc} :alias 'xyz {})
           '[:alias xray.yankee.zulu])))
  (testing "prefers candidates with the shortest alias-distance"
    (is (= (disambiguate '#{clojure.string clojure.core} :alias 's {})
           '[:alias clojure.string]))
    (is (= (disambiguate '#{clojure.string clojure.set} :alias 'st {})
           '[:alias clojure.string])))
  (testing "prefers shortest candidates when no other predicates match"
    (is (= (disambiguate '#{clojure.java.io clojure.set clojure.string}
                         :alias 'a {})
           '[:alias clojure.set])))
  (testing "changes type to :refer-all when top candidate is in old :refer-all"
    (is (= (disambiguate '#{clojure.set clojure.string}
                         :refer 'join
                         '{:old-ns-map {:refer-all #{clojure.set}}})
           '[:refer-all clojure.set]))))

(deftest ^:unit test-grow-ns-map
  (testing "finds basic imports, aliases, and refers"
    (is (= (grow-ns-map {} :import 'RegrowTestRecord '((RegrowTestRecord.)))
           '{:import #{slam.hound.regrow_test.RegrowTestRecord}}))
    (is (= (grow-ns-map {} :alias 'string '((string/join)))
           '{:alias {clojure.string string}}))
    (is (= (grow-ns-map {} :refer 'pprint '((pprint [])))
           '{:refer {clojure.pprint #{pprint}}})))
  (testing "honors old :refer :all"
    (is (= (grow-ns-map '{:old {:refer-all #{clojure.pprint}}}
                        :refer 'pprint '((pprint [])))
           '{:old {:refer-all #{clojure.pprint}}
             :refer-all #{clojure.pprint}})))
  (testing "finds capitalized vars"
    (is (= (grow-ns-map '{} :refer 'CapitalVar '((type CapitalVar)))
           '{:refer {slam.hound.regrow-test #{CapitalVar}}}))))

(deftest ^:unit test-regrow
  (testing "prefers capitalized vars referred in old ns to classes"
    (is (= (regrow '[{:old {:refer {slam.hound.regrow-test #{Pattern}}}}
                     ((def p Pattern))])
           '{:old {:refer {slam.hound.regrow-test #{Pattern}}}
             :refer {slam.hound.regrow-test #{Pattern}}}))
    (is (= (regrow '[{:old {:refer {my.ns #{BitSet}}}}
                     ((def bs BitSet))])
           '{:old {:refer {my.ns #{BitSet}}}
             :import #{java.util.BitSet}})))
  (testing "finds referred vars with strange names"
    (is (= (regrow '[{} ((assert +i-must-be-a-cl-user+))])
           '{:refer {slam.hound.regrow-test #{+i-must-be-a-cl-user+}}}))
    (is (= (regrow '[{} ((keyword? -+_$?!*><=''))])
           '{:refer {slam.hound.regrow-test #{-+_$?!*><=''}}}))
    (is (= (regrow '[{:old {:exclude {clojure.core #{/}}}
                      :exclude {clojure.core #{/}}}
                     ((keyword? /))])
           '{:old {:exclude {clojure.core #{/}}}
             :exclude {clojure.core #{/}}
             :refer {slam.hound.regrow-test #{/}}})))
  (testing "finds consumed references within syntax-quotes"
    (is (= (regrow '[{:name slam.hound.regrow-test}
                     ((eval `(instance? Named :foo)))])
           '{:name slam.hound.regrow-test
             :import #{clojure.lang.Named}}))))
