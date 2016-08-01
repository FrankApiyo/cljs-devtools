(ns devtools.utils.test
  (:require [cljs.test :refer-macros [is]]
            [clojure.walk :refer [postwalk]]
            [cljs.pprint :refer [pprint]]
            [goog.array :as garr]
            [goog.json :as json]
            [goog.object :as gobj]
            [devtools.util :refer-macros [oset oget]]
            [devtools.formatters.core :as f]
            [devtools.formatters.templating :refer [render-markup]]
            [devtools.prefs :refer [set-prefs!]]
            [devtools.defaults :as defaults]
            [clojure.string :as string]))

(defn reset-prefs-to-defaults! []
  (set-prefs! defaults/prefs))

; taken from https://github.com/purnam/purnam/blob/62bec5207621779a31c5adf3593530268aebb7fd/src/purnam/native/functions.cljs#L128-L145
; Copyright © 2014 Chris Zheng
(defn js-equals [v1 v2]
  (if (= v1 v2) true
                (let [t1 (js/goog.typeOf v1)
                      t2 (js/goog.typeOf v2)]
                  (cond (= "array" t1 t2)
                        (garr/equals v1 v2 js-equals)

                        (= "object" t1 t2)
                        (let [ks1 (.sort (js-keys v1))
                              ks2 (.sort (js-keys v2))]
                          (if (garr/equals ks1 ks2)
                            (garr/every
                              ks1
                              (fn [k]
                                (js-equals (aget v1 k) (aget v2 k))))
                            false))
                        :else
                        false))))

(defn collect-refs [template]
  (let [refs (atom [])
        catch-next (atom false)
        filter (fn [_ value]
                 (if @catch-next
                   (do
                     (reset! catch-next false)
                     (reset! refs (conj @refs value))
                     nil)
                   (do
                     (if (= value "object")
                       (reset! catch-next true))
                     value)))]
    (json/serialize template filter)
    @refs))

; note: not perfect just ad-hoc for our cases
(defn plain-js-obj? [o]
  (and (object? o) (not (coll? o))))

(defn pref [value]
  (if (keyword? value)
    (recur (devtools.prefs/pref value))
    value))

(defn resolve-pref-and-render-markup [value]
  (let [resolved-value (pref value)]
    (if (sequential? resolved-value)
      (render-markup resolved-value)
      resolved-value)))

(defn resolve-pref [k]
  (if (keyword? k)
    (resolve-pref-and-render-markup k)
    k))

(defn resolve-prefs [v]
  (postwalk resolve-pref v))

(defn resolve-tag [v]
  (or
    (if (sequential? v)
      (let [k (first v)]
        (if (keyword? k)
          (if (string/ends-with? (name k) "-tag")
            (let [resolved-tag (pref k)]
              (assert (sequential? resolved-tag) (str k " expected to resolve to a sequence, "
                                                      "got " resolved-tag " instead\n"
                                                      v))
              (assert (= 2 (count resolved-tag)))
              (concat [(first resolved-tag) {"style" (second resolved-tag)}] (rest v)))))))
    v))

(defn resolve-tags [v]
  (postwalk resolve-tag v))

(defn remove-empty-style [x]
  (if (and (map? x) (or (= (get x "style") "") (nil? (get x "style"))))
    (dissoc x "style")
    x))

(defn remove-empty-styles [v]
  (postwalk remove-empty-style v))

(defn collapsed? [v]
  (or (nil? v)
      (and (string? v) (empty? v))))

(defn remove-collapsed-element [x]
  (cond
    (vector? x) (vec (remove collapsed? x))
    (seq? x) (remove collapsed? x)
    :else x))

(defn remove-collapsed-elements [v]
  (postwalk remove-collapsed-element v))

(defn should-unroll? [o]
  (and (fn? o)
       (:unroll (meta o))))

(defn unroll-fns [v]
  (if (vector? v)
    (mapcat (fn [item] (if (should-unroll? item) (unroll-fns (item)) [(unroll-fns item)])) v)
    v))

(defn object-reference? [json-ml]
  (= (first json-ml) "object"))

(defn replace-refs-and-configs [json-ml]
  (if (array? json-ml)
    (if (object-reference? json-ml)
      (let [data (second json-ml)
            new-data (js-obj)]
        (assert (object? data))
        (if (oget data "object")
          (oset new-data ["object"] "##REF##"))
        (if (oget data "config")
          (oset new-data ["config"] "##CONFIG##"))
        #js ["object" new-data])
      (into-array (map replace-refs-and-configs json-ml)))
    json-ml))

(defn is-template [template expected & callbacks]
  (let [sanitized-template (replace-refs-and-configs template)
        refs (collect-refs template)
        expected-template (-> expected
                              (unroll-fns)
                              (resolve-tags)
                              (resolve-prefs)
                              (remove-empty-styles)
                              (remove-collapsed-elements)
                              (clj->js))]
    (is (js-equals sanitized-template expected-template))
    (when-not (empty? callbacks)
      (is (= (count refs) (count callbacks)) "number of refs and callbacks does not match")
      (loop [rfs refs
             cbs callbacks]
        (when-not (empty? cbs)
          (let [rf (first rfs)
                object (aget rf "object")
                config (aget rf "config")
                cb (first cbs)]
            (cb object config)
            (recur (rest rfs) (rest cbs))))))))

(defn patch-circular-references [obj & [parents]]
  (if (goog/isObject obj)
    (if (some #(identical? obj %) parents)
      "##CIRCULAR##"
      (let [new-parents (conj parents obj)]
        (doseq [key (gobj/getKeys obj)]
          (let [val (gobj/get obj key)
                patched-val (patch-circular-references val new-parents)]
            (if-not (identical? val patched-val)
              (gobj/set obj key patched-val))))
        obj))
    obj))

(defn safe-data-fn [f]
  (fn [value]
    (-> value
        (f)
        (patch-circular-references))))

; note: custom formatters api can return circular data structures when feeded with circular input data
;       we are not interested in exploring cycles, so these safe- methods remove cycles early on
(def safe-header (safe-data-fn f/header))
(def safe-body (safe-data-fn f/body))

(defn is-header [value expected & callbacks]
  (apply is-template (safe-header value) expected callbacks))

(defn is-body [value expected & callbacks]
  (apply is-template (safe-body value) expected callbacks))

(defn has-body? [value expected]
  (is (= (f/has-body value) expected)
      (if expected
        (str (pr-str value) " SHOULD return true to hasBody call")
        (str (pr-str value) " SHOULD return false to hasBody call"))))

(defn unroll [& args]
  (with-meta (apply partial (concat [mapcat] args)) {:unroll true}))

(defn match? [[returned expected]]
  (cond
    (string? expected) (= expected returned)
    (regexp? expected) (some? (re-matches expected returned))
    :else (throw (ex-info "invalid expected data type" {:expected expected :type (type expected)}))))

(defn match-seqs? [c1 c2]
  (if (= (count c1) (count c2))
    (every? match? (partition 2 (interleave c1 c2)))))

(defn pref-str [& args]
  (apply str (map #(if (keyword? %) (pref %) %) args)))

; -- console capture --------------------------------------------------------------------------------------------------------

(defonce captured-console-output (atom []))
(defonce original-console-api (atom nil))

(defn console-handler [orig kind & args]
  (let [transcript (str kind args)]
    (swap! captured-console-output conj transcript)
    (.apply orig js/console (to-array args))))

(defn store-console-api []
  {"log"   (oget js/window "console" "log")
   "warn"  (oget js/window "console" "warn")
   "info"  (oget js/window "console" "info")
   "error" (oget js/window "console" "error")})

(defn captured-console-api [original-api]
  {"log"   (partial console-handler (get original-api "log") "LOG: ")
   "warn"  (partial console-handler (get original-api "warn") "WARN: ")
   "info"  (partial console-handler (get original-api "info") "INFO: ")
   "error" (partial console-handler (get original-api "error") "ERROR: ")})

(defn set-console-api! [api]
  (oset js/window ["console" "log"] (get api "log"))
  (oset js/window ["console" "warn"] (get api "warn"))
  (oset js/window ["console" "info"] (get api "info"))
  (oset js/window ["console" "error"] (get api "error")))

(defn start-console-capture! []
  {:pre [(nil? @original-console-api)]}
  (reset! original-console-api (store-console-api))
  (set-console-api! (captured-console-api @original-console-api)))

(defn stop-console-capture! []
  {:pre [(some? @original-console-api)]}
  (set-console-api! @original-console-api)
  (reset! original-console-api nil))

(defn clear-captured-console-output! []
  (reset! captured-console-output []))

(defn get-captured-console-messages []
  @captured-console-output)

(defn with-captured-console [f]
  (start-console-capture!)
  (f)
  (stop-console-capture!))
