(ns tapestry.test-runner
  (:require [clojure.test :as t]
            [tapestry.core-test]
            [tapestry.queue-test]
            [tapestry.experimental-test]
            [tapestry.experimental.scope-test]))

(defn -main [& _]
  (let [{:keys [fail error] :or {fail 0 error 0}}
        (t/run-tests 'tapestry.core-test
                     'tapestry.queue-test
                     'tapestry.experimental-test
                     'tapestry.experimental.scope-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
