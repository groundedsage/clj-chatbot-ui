(ns app.chat-app
  (:require contrib.str
            #?(:clj [datahike.api :as dh])
            #?(:clj [datahike-jdbc.core])
            #?(:cljs [goog.userAgent :as ua])
            #?(:cljs [goog.labs.userAgent.platform :as platform])
            #?(:clj [nextjournal.markdown :as md])
            #?(:clj [nextjournal.markdown.transform :as md.transform])
            #?(:clj [hiccup2.core :as h])
            [clojure.string :as str]
            [contrib.str :refer [empty->nil]]
            [clojure.string :as str]
            [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom]
            [nano-id.core :refer [nano-id]]
            [hyperfiddle.electric-ui4 :as ui]
            #?(:clj [wkok.openai-clojure.api :as api])))

;; Can possibly remove the snapshot usage in next version of Electric
;; https://clojurians.slack.com/archives/C7Q9GSHFV/p1693947757659229?thread_ts=1693946525.050339&cid=C7Q9GSHFV

#?(:clj (def cfg {:store {:backend :mem :id "schemaless"}
                  :schema-flexibility :read}))

#?(:clj (defonce create-db (dh/create-database cfg)))
#?(:clj (defonce !dh-conn (dh/connect cfg)))
(e/def db) ; injected database ref; Electric defs are always dynamic

(def dh-schema
  [{:db/ident :folder/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :folder/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :prompt.folder/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :prompt.folder/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :prompt/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :conversation/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :conversation/topic
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :conversation/messages
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident :message/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :message/text
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :active-key-name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :key/value
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

;; transact schema to the db
#?(:clj (defonce dh-schema-tx (dh/transact !dh-conn {:tx-data dh-schema})))

#?(:cljs (defonce !view-main (atom :pre-conversation)))
#?(:cljs (defonce !view-main-prev (atom nil)))
#?(:cljs (defonce view-main-watcher (add-watch !view-main :main-prev (fn [_k _r os _ns]
                                                                       (println "this is os: " os)
                                                                       (when-not (or (= os :prompt-editor)
                                                                                   (= os :data-export)
                                                                                   (= os :settings))
                                                                         (reset! !view-main-prev os))))))

#?(:cljs (defonce !edit-folder (atom nil)))
#?(:cljs (defonce !active-conversation (atom nil)))
#?(:cljs (defonce !convo-dragged (atom nil)))
#?(:cljs (defonce !prompt-dragged (atom nil)))
#?(:cljs (defonce !folder-dragged-to (atom nil)))
#?(:cljs (defonce !data-export? (atom false)))
#?(:cljs (defonce !open-folders (atom #{})))

#?(:cljs (defonce !prompt-editor (atom {:action nil
                                        :name nil
                                        :text nil})))

#?(:cljs (def !select-prompt? (atom false)))
#?(:clj (defonce !stream-msgs (atom {}))) ;{:convo-id nil :content nil :streaming false}

(e/def stream-msgs (e/server (e/watch !stream-msgs)))
#?(:clj (defonce !gpt-model (atom "GPT-4")))

#?(:cljs (defonce !sidebar? (atom false)))
(e/def sidebar? (e/client (e/watch !sidebar?)))
#?(:cljs (defonce !prompt-sidebar? (atom false)))
(e/def prompt-sidebar? (e/client (e/watch !prompt-sidebar?)))

#?(:cljs (defonce !system-prompt (atom "")))

(e/def open-folders (e/client (e/watch !open-folders)))
(e/def view-main (e/client (e/watch !view-main)))
(e/def edit-folder (e/client (e/watch !edit-folder)))
(e/def active-conversation (e/client (e/watch !active-conversation)))
(e/def convo-dragged (e/client (e/watch !convo-dragged)))
(e/def prompt-dragged (e/client (e/watch !prompt-dragged)))
(e/def folder-dragged-to (e/client (e/watch !folder-dragged-to)))
(e/def data-export? (e/client (e/watch !data-export?)))
(e/def prompt-editor (e/client (e/watch !prompt-editor)))
(e/def select-prompt? (e/client (e/watch !select-prompt?)))
(e/def system-prompt (e/client (e/watch !system-prompt)))

(def new-chat-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-plus\"><path d=\"M12 5l0 14\"></path><path d=\"M5 12l14 0\"></path></svg>")
(def search-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-folder-plus\"><path d=\"M12 19h-7a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2h4l3 3h7a2 2 0 0 1 2 2v3.5\"></path><path d=\"M16 19h6\"></path><path d=\"M19 16v6\"></path></svg>")
(def no-data-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"mx-auto mb-3\"><path d=\"M12 5h9\"></path><path d=\"M3 10h7\"></path><path d=\"M18 10h1\"></path><path d=\"M5 15h5\"></path><path d=\"M14 15h1m4 0h2\"></path><path d=\"M3 20h9m4 0h3\"></path><path d=\"M3 3l18 18\"></path></svg>")
(def side-bar-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24\" height=\"24\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-arrow-bar-left\"><path d=\"M4 12l10 0\"></path><path d=\"M4 12l4 4\"></path><path d=\"M4 12l4 -4\"></path><path d=\"M20 4l0 16\"></path></svg>")
(def user-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"30\" height=\"30\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-user\"><path d=\"M8 7a4 4 0 1 0 8 0a4 4 0 0 0 -8 0\"></path><path d=\"M6 21v-2a4 4 0 0 1 4 -4h4a4 4 0 0 1 4 4v2\"></path></svg>")
(def bot-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"30\" height=\"30\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-robot\"><path d=\"M7 7h10a2 2 0 0 1 2 2v1l1 1v3l-1 1v3a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-3l-1 -1v-3l1 -1v-1a2 2 0 0 1 2 -2z\"></path><path d=\"M10 16h4\"></path><circle cx=\"8.5\" cy=\"11.5\" r=\".5\" fill=\"currentColor\"></circle><circle cx=\"15.5\" cy=\"11.5\" r=\".5\" fill=\"currentColor\"></circle><path d=\"M9 7l-1 -4\"></path><path d=\"M15 7l1 -4\"></path></svg>")
(def edit-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-edit\"><path d=\"M7 7h-1a2 2 0 0 0 -2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2 -2v-1\"></path><path d=\"M20.385 6.585a2.1 2.1 0 0 0 -2.97 -2.97l-8.415 8.385v3h3l8.385 -8.415z\"></path><path d=\"M16 5l3 3\"></path></svg>")
(def delete-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-trash\"><path d=\"M4 7l16 0\"></path><path d=\"M10 11l0 6\"></path><path d=\"M14 11l0 6\"></path><path d=\"M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2 -2l1 -12\"></path><path d=\"M9 7v-3a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v3\"></path></svg>")
(def copy-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-copy\"><path d=\"M8 8m0 2a2 2 0 0 1 2 -2h8a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2z\"></path><path d=\"M16 8v-2a2 2 0 0 0 -2 -2h-8a2 2 0 0 0 -2 2v8a2 2 0 0 0 2 2h2\"></path></svg>")
(def send-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-send\"><path d=\"M10 14l11 -11\"></path><path d=\"M21 3l-6.5 18a.55 .55 0 0 1 -1 0l-3.5 -7l-7 -3.5a.55 .55 0 0 1 0 -1l18 -6.5\"></path></svg>")
(def msg-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-message\"><path d=\"M8 9h8\"></path><path d=\"M8 13h6\"></path><path d=\"M18 4a3 3 0 0 1 3 3v8a3 3 0 0 1 -3 3h-5l-5 3v-3h-2a3 3 0 0 1 -3 -3v-8a3 3 0 0 1 3 -3h12z\"></path></svg>")
(def tick-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-check\"><path d=\"M5 12l5 5l10 -10\"></path></svg>")
(def x-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-x\"><path d=\"M18 6l-12 12\"></path><path d=\"M6 6l12 12\"></path></svg>")
(def settings-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-settings\"><path d=\"M10.325 4.317c.426 -1.756 2.924 -1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543 -.94 3.31 .826 2.37 2.37a1.724 1.724 0 0 0 1.065 2.572c1.756 .426 1.756 2.924 0 3.35a1.724 1.724 0 0 0 -1.066 2.573c.94 1.543 -.826 3.31 -2.37 2.37a1.724 1.724 0 0 0 -2.572 1.065c-.426 1.756 -2.924 1.756 -3.35 0a1.724 1.724 0 0 0 -2.573 -1.066c-1.543 .94 -3.31 -.826 -2.37 -2.37a1.724 1.724 0 0 0 -1.065 -2.572c-1.756 -.426 -1.756 -2.924 0 -3.35a1.724 1.724 0 0 0 1.066 -2.573c-.94 -1.543 .826 -3.31 2.37 -2.37c1 .608 2.296 .07 2.572 -1.065z\"></path><path d=\"M9 12a3 3 0 1 0 6 0a3 3 0 0 0 -6 0\"></path></svg>")
(def folder-arrow-icon "<svg xmlns=\"http://www.w3.org/2000/sv\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-caret-right\"><path d=\"M10 18l6 -6l-6 -6v12\"></path></svg>")
(def folder-arrow-icon-down "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-caret-down\"><path d=\"M6 10l6 6l6 -6h-12\"></path></svg>")
(def transfer-data-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-file-export\"><path d=\"M14 3v4a1 1 0 0 0 1 1h4\"></path><path d=\"M11.5 21h-4.5a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2h7l5 5v5m-5 6h7m-3 -3l3 3l-3 3\"></path></svg>")
(def key-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-key\"><path d=\"M16.555 3.843l3.602 3.602a2.877 2.877 0 0 1 0 4.069l-2.643 2.643a2.877 2.877 0 0 1 -4.069 0l-.301 -.301l-6.558 6.558a2 2 0 0 1 -1.239 .578l-.175 .008h-1.172a1 1 0 0 1 -.993 -.883l-.007 -.117v-1.172a2 2 0 0 1 .467 -1.284l.119 -.13l.414 -.414h2v-2h2v-2l2.144 -2.144l-.301 -.301a2.877 2.877 0 0 1 0 -4.069l2.643 -2.643a2.877 2.877 0 0 1 4.069 0z\"></path><path d=\"M15 9h.01\"></path></svg>")
(def prompt-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"tabler-icon tabler-icon-bulb-filled\"><path d=\"M4 11a1 1 0 0 1 .117 1.993l-.117 .007h-1a1 1 0 0 1 -.117 -1.993l.117 -.007h1z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M12 2a1 1 0 0 1 .993 .883l.007 .117v1a1 1 0 0 1 -1.993 .117l-.007 -.117v-1a1 1 0 0 1 1 -1z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M21 11a1 1 0 0 1 .117 1.993l-.117 .007h-1a1 1 0 0 1 -.117 -1.993l.117 -.007h1z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M4.893 4.893a1 1 0 0 1 1.32 -.083l.094 .083l.7 .7a1 1 0 0 1 -1.32 1.497l-.094 -.083l-.7 -.7a1 1 0 0 1 0 -1.414z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M17.693 4.893a1 1 0 0 1 1.497 1.32l-.083 .094l-.7 .7a1 1 0 0 1 -1.497 -1.32l.083 -.094l.7 -.7z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M14 18a1 1 0 0 1 1 1a3 3 0 0 1 -6 0a1 1 0 0 1 .883 -.993l.117 -.007h4z\" fill=\"currentColor\" stroke-width=\"0\"></path><path d=\"M12 6a6 6 0 0 1 3.6 10.8a1 1 0 0 1 -.471 .192l-.129 .008h-6a1 1 0 0 1 -.6 -.2a6 6 0 0 1 3.6 -10.8z\" fill=\"currentColor\" stroke-width=\"0\"></path></svg>")
(def ext-link-icon "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" class=\"inline mr-1\"><path d=\"M11 7h-5a2 2 0 0 0 -2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2 -2v-5\"></path><path d=\"M10 14l10 -10\"></path><path d=\"M15 4l5 0l0 5\"></path></svg>")

#?(:cljs (defn mobile-device? []
           (or (or ua/IPHONE
                 ua/PLATFORM_KNOWN_ ua/ASSUME_IPHONE
                 (platform/isIphone))
             (or ua/ANDROID
               ua/PLATFORM_KNOWN_ ua/ASSUME_ANDROID
               (platform/isAndroid)))))

#?(:clj (defn lowercase-includes? [s1 s2]
          (and (string? s1) (string? s2)
            (clojure.string/includes? (clojure.string/lower-case s1) (clojure.string/lower-case s2)))))

#?(:clj (defn parse-text [s]
          (->> (md/parse s)
            md.transform/->hiccup
            h/html
            str)))

#?(:clj  (defn fetch-convo-messages [db convo-id-str]
           (sort-by first < (dh/q '[:find ?msg-created ?msg-id ?msg-text ?msg-role
                                    :in $ ?conv-id
                                    :where
                                    [?e :conversation/id ?conv-id]
                                    [?e :conversation/messages ?msg]
                                    [?msg :message/id ?msg-id]
                                    [?msg :message/role ?msg-role]
                                    [?msg :message/text ?msg-text]
                                    [?msg :message/created ?msg-created]]
                              db convo-id-str))))

#?(:clj (defn process-chunk [convo-id data]
          (let [delta (get-in data [:choices 0 :delta])
                content (:content delta)]
            (if content
              (swap! !stream-msgs update-in [convo-id :content] (fn [old-content] (str old-content content)))
              (do
                (swap! !stream-msgs assoc-in [convo-id :streaming] false)
                (let [tx-msg (:content (get @!stream-msgs convo-id))]
                  (dh/transact !dh-conn [{:conversation/id convo-id
                                          :conversation/messages [{:message/id (nano-id)
                                                                   :message/text tx-msg
                                                                   :message/role :assistant
                                                                   :message/created (System/currentTimeMillis)}]}]))
                (swap! !stream-msgs assoc-in [convo-id :content] nil))))))

#?(:clj (defn stream-chat-completion [convo-id msg-list model api-key]
          (swap! !stream-msgs assoc-in [convo-id :streaming] true)
          (try (api/create-chat-completion
                   {:model model
                    :messages msg-list
                    :stream true
                    :on-next #(process-chunk convo-id %)}
                   {:api-key api-key})
              (catch Exception e
                (println "This is the exception: " e)))))

(e/defn PromptInput [{:keys [convo-id messages selected-model temperature]}]
  ;; TODO: add the system prompt to the message list
  (let [api-key (e/server (e/offload #(dh/q '[:find ?v .
                                              :where
                                              [?e :active-key-name ?name]
                                              [?k :key/name ?name]
                                              [?k :key/value ?v]] db)))]
    (dom/div (dom/props {:class (str (if (mobile-device?) "bottom-8" "bottom-0") " absolute left-0 w-full border-transparent bg-gradient-to-b from-transparent via-white to-white pt-6 dark:border-white/20 dark:via-[#343541] dark:to-[#343541] md:pt-2")})
      (dom/div (dom/props {:class "stretch mx-2 mt-4 flex flex-row gap-3 last:mb-2 md:mx-4 md:mt-[52px] md:last:mb-6 lg:mx-auto lg:max-w-3xl"})
        (dom/div (dom/props {:class "flex flex-col w-full gap-2"})
          (when select-prompt?
            (let [prompts (e/server  (sort-by first (dh/q '[:find ?name ?prompt-id ?text
                                                            :where
                                                            [?e :prompt/id ?prompt-id]
                                                            [?e :prompt/name ?name]
                                                            [?e :prompt/text ?text]]
                                                      db)))]
              (when (seq prompts)
                (e/for [[prompt-name prompt-id prompt-text] prompts]
                  (dom/ol (dom/props {:class "flex flex-col w-full"})
                    (dom/li (dom/props {:class "flex justify-center py-2 px-4 bg-black cursor-pointer relative mx-2 sm:mx-4 flex w-full flex-grow flex-col rounded-md border border-black/10"
                                        :style {:height "44px"}})
                      (dom/on "click" (e/fn [_] (let [elem (.getElementById js/document "prompt-input")]
                                                  (set! (.-value elem) prompt-text)
                                                  (reset! !select-prompt? false))))
                      (dom/text prompt-name)))))))
          (dom/div (dom/props {:class "relative flex w-full flex-grow flex-col rounded-md border border-black/10 bg-white shadow-[0_0_10px_rgba(0,0,0,0.10)] dark:border-gray-900/50 dark:bg-[#40414F] dark:text-white dark:shadow-[0_0_15px_rgba(0,0,0,0.10)] sm:mx-4"})
            (dom/textarea (dom/props {:id "prompt-input"
                                      :class "sm:h-11 m-0 w-full resize-none border-0 bg-transparent p-0 py-2 pr-8 pl-10 text-black dark:bg-transparent dark:text-white md:py-3 md:pl-10"

                                      :placeholder "Type a message or type \"/\" to select a prompt..."})
              (dom/on "keyup" (e/fn [e] (if-some [v (empty->nil (.. e -target -value))]
                                          (when (= "/" (first v))
                                            (reset! !select-prompt? true))
                                          (reset! !select-prompt? false))))
              (dom/on "keydown" (e/fn [e]
                                  (when (= "Enter" (.-key e))
                                    (.preventDefault e)
                                    (when-some [v (empty->nil (.. e -target -value))]
                                      (when-not (str/blank? v)
                                        (if-not @!active-conversation
                                          (let [convo-id (nano-id)
                                                sys-prompt @!system-prompt]
                                            (println "the value: " v)
                                            (println "the system prompt on keydown: " @!system-prompt)
                                            (e/server (let [time-point (System/currentTimeMillis)
                                                            model selected-model
                                                            temp temperature
                                                            message-list [{:role "system"
                                                                           :content sys-prompt}
                                                                          {:role "user"
                                                                           :content v}]]
                                                        (stream-chat-completion convo-id message-list model api-key)
                                                        (e/offload #(dh/transact !dh-conn [{:conversation/id convo-id
                                                                                            :conversation/model model
                                                                                            :conversation/temp temp
                                                                                            :conversation/topic v
                                                                                            :conversation/messages [{:message/id (nano-id)
                                                                                                                     :message/text sys-prompt
                                                                                                                     :message/role :system
                                                                                                                     :message/created time-point}
                                                                                                                    {:message/id (nano-id)
                                                                                                                     :message/text v
                                                                                                                     :message/role :user
                                                                                                                     :message/created time-point}]
                                                                                            :conversation/system-prompt sys-prompt
                                                                                            :conversation/created time-point}]))
                                                        (println "the system prompt: " sys-prompt)
                                                        (println "the message list: " message-list)))
                                            (reset! !active-conversation convo-id)
                                            (reset! !view-main :conversation))
                                          (e/server
                                                                                                  ;; Add messages to an existing conversation
                                            (let [{:keys [db/id conversation/model]} (e/offload #(dh/pull @!dh-conn '[:db/id :conversation/model] [:conversation/id convo-id]))
                                                  messages (e/snapshot messages)]
                                              (when convo-id
                                                (let [message-id (nano-id)
                                                      time-point (System/currentTimeMillis)
                                                      new-message {:message/id message-id
                                                                   :message/text v
                                                                   :message/role :user
                                                                   :message/created time-point}
                                                      message-list (conj (mapv (fn [[_ _ msg role]]
                                                                                 {:content msg
                                                                                  :role role}) messages)
                                                                     {:content v
                                                                      :role "user"})]

                                                  (e/offload #(try (dh/transact !dh-conn [{:conversation/id convo-id
                                                                                           :conversation/messages new-message}])
                                                                (catch Exception e
                                                                  (println "Caught exception " e))))
                                                  (stream-chat-completion convo-id message-list model api-key))))
                                            nil))))))))
            (dom/button (dom/props {:class "absolute right-2 top-2 rounded-sm p-1 text-neutral-800 opacity-60 hover:bg-neutral-200 hover:text-neutral-900 dark:bg-opacity-50 dark:text-neutral-100 dark:hover:text-neutral-200"})
              (set! (.-innerHTML dom/node) send-icon))))))))

(e/defn BotMsg [msg]
  (dom/div (dom/props {:class "group md:px-4 border-b border-black/10 bg-white text-gray-800 dark:border-gray-900/50 dark:bg-[#343541] dark:text-gray-100"})
      (dom/div (dom/props {:class "relative m-auto flex p-4 text-base md:max-w-2xl md:gap-6 md:py-6 lg:max-w-2xl lg:px-0 xl:max-w-3xl"})
        (dom/div (dom/props {:class "min-w-[40px] text-right font-bold"})
          (set! (.-innerHTML dom/node) bot-icon))
        (dom/div (dom/props {:class "prose whitespace-pre-wrap dark:prose-invert flex-1"})
          (dom/text msg))
        (dom/div (dom/props {:class "md:-mr-8 ml-1 md:ml-0 flex flex-col md:flex-row gap-4 md:gap-1 items-center md:items-start justify-end md:justify-start"})
          (dom/button (dom/props {:class "invisible group-hover:visible focus:visible text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"})
            (set! (.-innerHTML dom/node) edit-icon))
          (dom/button (dom/props {:class "invisible group-hover:visible focus:visible text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"})
            (set! (.-innerHTML dom/node) delete-icon))))))

(e/defn UserMsg [msg]
  (dom/div (dom/props {:class "group md:px-4 border-b border-black/10 bg-white text-gray-800 dark:border-gray-900/50 dark:bg-[#343541] dark:text-gray-100"})
    (dom/div (dom/props {:class "relative m-auto flex p-4 text-base md:max-w-2xl md:gap-6 md:py-6 lg:max-w-2xl lg:px-0 xl:max-w-3xl"})
      (dom/div (dom/props {:class "min-w-[40px] text-right font-bold"})
        (set! (.-innerHTML dom/node) user-icon))
      (dom/div (dom/props {:class "prose whitespace-pre-wrap dark:prose-invert flex-1"})
        #_(dom/div
          (let [server-rendered-html (e/server (parse-text msg))]
            (set! (.-innerHTML dom/node) server-rendered-html)))
        (dom/text msg))
      (dom/div (dom/props {:class "md:-mr-8 ml-1 md:ml-0 flex flex-col md:flex-row gap-4 md:gap-1 items-center md:items-start justify-end md:justify-start"})
        (dom/button (dom/props {:class "invisible group-hover:visible focus:visible text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"})
          (set! (.-innerHTML dom/node) edit-icon))
        (dom/button (dom/props {:class "invisible group-hover:visible focus:visible text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"})
          (set! (.-innerHTML dom/node) delete-icon))))))

(e/defn RenderMsg [msg]
  (let [[created id msg role] msg]
    (case role
      :user (UserMsg. msg)
      :assistant  (BotMsg. msg)
      :system nil #_(dom/div (dom/props {:class "group md:px-4 border-b border-black/10 bg-white text-gray-800 dark:border-gray-900/50 dark:bg-[#343541] dark:text-gray-100"})
                (dom/div (dom/props {:class "relative m-auto flex p-4 text-base md:max-w-2xl md:gap-6 md:py-6 lg:max-w-2xl lg:px-0 xl:max-w-3xl"})
                  (dom/div (dom/props {:class "min-w-[40px] text-right font-bold"})
                    (set! (.-innerHTML dom/node) bot-icon))
                  (dom/div (dom/props {:class "prose whitespace-pre-wrap dark:prose-invert flex-1"})
                    (dom/text (str role " - " msg "  " created)))
                  (dom/div (dom/props {:class "md:-mr-8 ml-1 md:ml-0 flex flex-col md:flex-row gap-4 md:gap-1 items-center md:items-start justify-end md:justify-start"})
                    (dom/button (dom/props {:class "invisible group-hover:visible focus:visible text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300"})
                      (set! (.-innerHTML dom/node) delete-icon))))))))

(e/defn Conversation []
  (let [convo-id active-conversation
        [model temp convo-system-prompt] (e/server (e/offload #(dh/q '[:find [?model ?temp ?system-prompt]
                                                                 :in $ ?conv-id
                                                                 :where
                                                                 [?e :conversation/id ?conv-id]
                                                                 [?e :conversation/model ?model]
                                                                 [?e :conversation/temp ?temp]
                                                                 [?e :conversation/system-prompt ?system-prompt]]
                                                           db convo-id)))
        messages (e/server (e/offload #(fetch-convo-messages db convo-id)))]

    (dom/div (dom/props {:class "flex flex-col stretch"})
      (dom/div (dom/props {:class "sticky top-0 z-10 flex justify-center border border-b-neutral-300 bg-neutral-100 py-2 text-sm text-neutral-500 dark:border-none dark:bg-[#444654] dark:text-neutral-200"})
        (dom/text (str "Model " model " | " "Temp " temp)))
      (println "the messages: " messages)
      (e/for [msg messages]
        (RenderMsg. msg))
      (when (:streaming (get stream-msgs convo-id))
        (when-let [content (:content (get stream-msgs convo-id))]
          (BotMsg. content)))
      (PromptInput. {:convo-id convo-id
                     :messages messages}))))

(e/defn PreConversation []
  (let [!selected-model (atom "gpt-3.5-turbo")
        selected-model (e/watch !selected-model)
        !temperature (atom "1")
        temperature (e/watch !temperature)
        db-system-prompt (e/server (e/offload #(ffirst (dh/q '[:find ?prompt
                                                              :where
                                                              [?e :global-system-prompt ?prompt]]
                                                        db))))
        _ (reset! !system-prompt (if db-system-prompt db-system-prompt ""))
        _ (println "the new system prompt: " system-prompt)
        _ (reset! !active-conversation nil)]
    (dom/div (dom/props {:class "mx-auto flex flex-col space-y-5 md:space-y-10 px-3 pt-5 md:pt-12 sm:max-w-[600px]"})
      (dom/div (dom/props {:class "text-center text-3xl font-semibold text-gray-800 dark:text-gray-100"})
        (dom/text "Chatbot UI"))
      (dom/div (dom/props {:class "flex h-full flex-col space-y-4 rounded-lg border border-neutral-200 p-4 dark:border-neutral-600"})
        (dom/div (dom/props {:class "flex flex-col"})
          (dom/label (dom/props {:class "mb-2 text-left text-neutral-700 dark:text-neutral-400"})
            (dom/text "Model"))
          (dom/div (dom/props {:class "w-full rounded-lg border border-neutral-200 bg-transparent pr-2 text-neutral-900 dark:border-neutral-600 dark:text-white"})
            (dom/select (dom/props {:class "w-full bg-transparent p-2"
                                    :placeholder "Select a model"})
              (dom/on "change" (e/fn [e] (reset! !selected-model (.-value (.-target e)))))
              (dom/option
                (dom/props {:class "dark:bg-[#343541] dark:text-white"
                            :value "GPT-3.5-turbo"})
                (dom/text "Default (GPT-3.5)"))
              (dom/option
                (dom/props {:class "dark:bg-[#343541] dark:text-white"
                            :value "GPT-4"})
                (dom/text "GPT-4")))))
        (dom/div (dom/props {:class "w-full mt-3 text-left text-neutral-700 dark:text-neutral-400 flex items-center"})
          (dom/a (dom/props {:class "flex items-center gap-1"
                             :href "https://platform.openai.com/account/usage"
                             :target "_blank"})
            (set! (.-innerHTML dom/node) ext-link-icon)
            (dom/text "View Account Usage")))
        (dom/div (dom/props {:class "flex flex-col"})
          (dom/label
            (dom/props {:class "mb-2 text-left text-neutral-700 dark:text-neutral-400"})
            (dom/text "System Prompt"))
          (dom/textarea (dom/props {:class "w-full rounded-lg border border-neutral-200 bg-transparent px-4 py-3 text-neutral-900 dark:border-neutral-600 dark:text-neutral-100 resize-none"
                                    :placeholder "Type your system prompt here or set globally in settings."
                                    :value system-prompt})
            (dom/on "keyup" (e/fn [e]
                              (if-some [v (empty->nil (.. e -target -value))]
                                (reset! !system-prompt (str/trim v))
                                (reset! !system-prompt ""))))))
        (dom/div (dom/props {:class "flex flex-col"})
          (dom/label (dom/props {:class "mb-2 text-left text-neutral-700 dark:text-neutral-400"})
            (dom/text "Temperature"))
          (dom/span (dom/props {:class "text-[12px] text-black/50 dark:text-white/50 text-sm"})
            (dom/text "Higher values like 0.8 will make the output more random, while lower values like 0.2 will make it more focused and deterministic."))
          (dom/span (dom/props {:class "mt-2 mb-1 text-center text-neutral-900 dark:text-neutral-100"})
            (dom/text temperature))
          (dom/input (dom/props {:class "cursor-pointer"
                                 :type "range"
                                 :min "0"
                                 :max "1"
                                 :step "0.1"
                                 :value temperature})
            (dom/on "change" (e/fn [e]
                               (println "This is the temperature value: " (.-value (.-target e)))
                               (reset! !temperature (.-value (.-target e))))))
          (dom/ul (dom/props {:class "w mt-2 pb-8 flex justify-between px-[24px] text-neutral-900 dark:text-neutral-100"})
            (e/for [precision ["Precise" "Neutral" "Creative"]]
              (dom/li (dom/props {:class "flex justify-center"})
                (dom/span (dom/props {:class "absolute"})
                  (dom/text precision))))))))
    (println "The system prompt before PromptInput: " system-prompt)
    (PromptInput. {:selected-model selected-model
                   :temperature temperature})))

(e/defn Settings []
  (let [!key-name (atom nil)
        key-name (e/watch !key-name)
        !key-v (atom nil)
        key-v (e/watch !key-v)
        !delete-key (atom nil)
        delete-key (e/watch !delete-key)
        current-keys (e/server (e/offload #(sort-by first < (dh/q '[:find ?created ?key-name ?e
                                                                   :where
                                                                   [?e :key/name ?key-name]
                                                                   [?e :key/created ?created]]
                                                             db))))
        [active-key-eid active-key-name] (e/server (e/offload #(first (dh/q '[:find ?e ?active-key-name
                                                                             :where
                                                                             [?e :active-key-name ?active-key-name]]
                                                                       db))))
        [db-system-prompt-eid db-system-prompt] (e/server (e/offload #(dh/q '[:find [?e ?prompt]
                                                                             :where
                                                                             [?e :global-system-prompt ?prompt]]
                                                                       db)))
        !system-prompt (if db-system-prompt
                         (atom db-system-prompt)
                         (atom nil))
        system-prompt (e/watch !system-prompt)]

    (dom/div (dom/props {:class "mx-auto flex flex-col space-y-5 md:space-y-10 px-3 pt-5 md:pt-12 sm:max-w-[600px]"})
      (dom/div (dom/props {:class "text-center text-3xl font-semibold text-gray-800 dark:text-gray-100"})
        (dom/text "Settings"))
      (dom/div (dom/props {:class "flex h-full flex-col space-y-4 rounded-lg border border-neutral-200 p-4 dark:border-neutral-600"})
        (dom/div (dom/props {:class "flex items-center gap-4 justify-center text-l font-semibold text-gray-800 dark:text-gray-100"})
          (dom/text "Global System Prompt"))
        (dom/textarea (dom/props {:class "w-full rounded-lg border border-neutral-200 bg-transparent px-4 py-3 text-neutral-900 dark:border-neutral-600 dark:text-neutral-100 resize-none"
                                  :placeholder "Type your system prompt here..."
                                  :value system-prompt})
          (dom/on "keyup" (e/fn [e]
                            (if-some [v (empty->nil (.. e -target -value))]
                              (reset! !system-prompt v)
                              (reset! !system-prompt nil)))))
        (dom/button
          (dom/props {:class (str (when (or (not system-prompt) (= db-system-prompt system-prompt)) "opacity-0") " w-44 border py-2 px-4 bg-[#202123] rounded hover:bg-white hover:text-black self-end")
                      :disabled (not system-prompt)})
          (dom/on "click" (e/fn [_]
                            (let [global-prompt system-prompt]
                              (if db-system-prompt
                                (e/server (e/offload #(dh/transact !dh-conn [{:db/id db-system-prompt-eid
                                                                              :global-system-prompt global-prompt}]))
                                  nil)
                                (e/server (e/offload #(dh/transact !dh-conn [{:global-system-prompt global-prompt}]))
                                  nil)))))
          (dom/text "Save"))
        (dom/div (dom/props {:class "flex items-center gap-4 justify-center text-l font-semibold text-gray-800 dark:text-gray-100"})
          (dom/text "API Keys"))
        (dom/div (dom/props {:class "flex flex-col gap-4"})
          (dom/ol
            (e/for [[_added key-name eid] current-keys]
              (dom/li (dom/props {:class (str (when delete-key "bg-red-400") " flex text-md gap-4 items-center justify-between")})
                (dom/div (dom/props {:class "cursor flex items-center gap-2"})
                  (dom/on "click" (e/fn [_] (e/server

                                              (e/offload #(do (dh/transact !dh-conn [{:db/id active-key-eid
                                                                                      :active-key-name key-name}])
                                                            nil)))))
                  (set! (.-innerHTML dom/node) key-icon)
                  (dom/text key-name))
                (dom/div (dom/props {:class "cursor-pointer flex gap-4 items-center"})
                  (when (= active-key-name key-name)
                    (dom/div (dom/props {:class "text-green-500 text-sm"})
                      (dom/text "active")))
                  (dom/button (dom/props {:class "flex items-center justify-center hover:border rounded"
                                          :style {:height "44px"
                                                  :width "44px"}})
                    (dom/on "click" (e/fn [_] (e/server (e/offload #(do (when (= active-key-name key-name)
                                                                          (dh/transact !dh-conn [{:db/id active-key-eid
                                                                                                  :active-key-name (str :no-active-key)}]))
                                                                      (do (dh/transact !dh-conn [[:db.fn/retractEntity eid]])
                                                                        nil))))))
                    (set! (.-innerHTML dom/node) delete-icon))))))
          (dom/div (dom/props {:class (str (when (seq current-keys) "border-t") " p-4 gap-4 flex flex-col border-neutral-200")})
            (dom/div (dom/props {:class "flex items-center gap-4"})
              (dom/label (dom/props {:class "w-16 text-neutral-700 dark:text-neutral-400"})
                (dom/text "Name"))
              (dom/input (dom/props {:class "w-full rounded-lg border border-neutral-200 bg-transparent px-4 py-3 text-neutral-900 dark:border-neutral-600 dark:text-neutral-100"
                                     :placeholder "key name..."
                                     :value key-name})
                (dom/on "keyup" (e/fn [e]
                                  (if-some [v (empty->nil (.. e -target -value))]
                                    (reset! !key-name v)
                                    (reset! !key-name nil))))))
            (dom/div (dom/props {:class "flex items-center gap-4"})
              (dom/label (dom/props {:class "w-16 text-neutral-700 dark:text-neutral-400"})
                (dom/text "Key"))
              (dom/input (dom/props {:class "w-full rounded-lg border border-neutral-200 bg-transparent px-4 py-3 text-neutral-900 dark:border-neutral-600 dark:text-neutral-100"
                                     :placeholder "shh..."
                                     :value key-v})
                (dom/on "keyup" (e/fn [e]
                                  (if-some [v (empty->nil (.. e -target -value))]
                                    (reset! !key-v v)
                                    (reset! !key-v nil))))))
            (ui/button
              (do (let [key-name @!key-name
                        key-v @!key-v
                        key-list? (seq (e/snapshot current-keys))]
                    (when (and key-v key-name)
                      (e/server
                        (println "this is the key click server before offload")
                        (e/offload #(let [base-tx [{:key/name key-name
                                                    :key/value key-v
                                                    :key/created (System/currentTimeMillis)}]
                                          tx-data (if-not key-list?
                                                    (conj base-tx {:active-key-name key-name})
                                                    base-tx)]
                                      (println "this is the key click server before transact")
                                      (dh/transact !dh-conn tx-data)
                                      (println "this is the key click server after transact")
                                      nil)))))
                (reset! !key-name nil)
                (reset! !key-v nil))
              (dom/div (dom/props {:class "border py-2 px-4 bg-[#202123] rounded hover:bg-white hover:text-black"})
                (dom/text "Add Key")))))))))

(e/defn ConversationList [conversations]
  (let [inside-folder-atom? (atom false)
        inside-folder? (e/watch inside-folder-atom?)
        !edit-conversation (atom false)
        edit-conversation (e/watch !edit-conversation)]
    (dom/div (dom/props {:class "pt-2 flex-grow"})
      (dom/on "dragover" (e/fn [e] (.preventDefault e)))
      (dom/on "dragenter" (e/fn [_] (.requestAnimationFrame js/window
                                      #(reset! !folder-dragged-to :default))))
      (dom/on "dragleave" (e/fn [_] (fn [_] (reset! !folder-dragged-to nil))))
      (dom/on "drop" (e/fn [_]
                       (let [convo-id @!convo-dragged]
                         (e/server
                           (e/offload
                             #(do
                                (println "Dropped convo-id on default: " convo-id)
                                (when-let [eid (dh/q '[:find ?e .
                                                       :in $ ?convo-id
                                                       :where
                                                       [?e :conversation/id ?convo-id]
                                                       [?e :conversation/folder]]
                                                 db convo-id)]
                                  (println "the folder: " eid)
                                  (dh/transact !dh-conn [[:db/retract eid :conversation/folder]])))))
                         (reset! !folder-dragged-to nil)
                         (reset! !convo-dragged nil))))
      (dom/div (dom/props {:class (str (when-not inside-folder? "gap-1 ") "flex w-full flex-col")})
        (e/for [[created eid convo-id topic folder-name] conversations]
          (when folder-name (reset! inside-folder-atom? folder-name))
          (let [editing? (= convo-id (:convo-id edit-conversation))]
            (dom/div (when folder-name (dom/props {:class "ml-5 gap-2 border-l pl-2"}))
              (dom/div (dom/props {:class "relative flex items-center"})
                (if-not (and editing? (= :edit (:action edit-conversation)))
                  (dom/button (dom/props {:class (str (when (= active-conversation convo-id) "bg-[#343541]/90 ") "flex w-full cursor-pointer items-center gap-3 rounded-lg p-3 text-sm transition-colors duration-200 hover:bg-[#343541]/90")
                                          :draggable true})
                    (set! (.-innerHTML dom/node) msg-icon)
                    (dom/on "click" (e/fn [_]
                                      (reset! !active-conversation convo-id)
                                      (reset! !view-main :conversation)))
                    (dom/on "dragstart" (e/fn [_] (reset! !convo-dragged convo-id)))
                    (dom/div (dom/props {:class "relative max-h-5 flex-1 overflow-hidden text-ellipsis whitespace-nowrap break-all text-left text-[12.5px] leading-3 pr-1"})
                      (dom/text topic)))
                  (dom/div (dom/props {:class "flex w-full items-center gap-3 rounded-lg bg-[#343541]/90 p-3"})
                    (set! (.-innerHTML dom/node) msg-icon)
                    (dom/input (dom/props {:class "mr-12 flex-1 overflow-hidden overflow-ellipsis border-neutral-400 bg-transparent text-left text-[12.5px] leading-3 text-white outline-none focus:border-neutral-100"
                                           :value topic})
                      (dom/on "keydown" (e/fn [e]
                                          (when (= "Enter" (.-key e))
                                            (when-some [v (empty->nil (.. e -target -value))]
                                              (let [new-topic (:changes @!edit-conversation)]
                                                (e/server
                                                  (e/offload #(dh/transact !dh-conn [{:db/id [:conversation/id convo-id]
                                                                                      :conversation/topic new-topic}]))
                                                  nil)
                                                (reset! !edit-conversation false))))))
                      (dom/on "keyup" (e/fn [e]
                                        (when-some [v (empty->nil (.. e -target -value))]
                                          (swap! !edit-conversation assoc :changes v))))
                      (.focus dom/node))))
                (when (= convo-id active-conversation)
                  (dom/div (dom/props {:class "absolute right-1 z-10 flex text-gray-300"})
                    (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                      (set! (.-innerHTML dom/node) (if editing? tick-icon edit-icon))
                      (if editing?
                        (dom/on "click" (e/fn [_]
                                          (case (:action edit-conversation)
                                            :delete (do
                                                      (e/server (e/offload #(dh/transact !dh-conn [(dh/transact !dh-conn [[:db.fn/retractEntity eid]])]))
                                                        nil)
                                                      (when (= convo-id @!active-conversation)
                                                        (reset! !active-conversation nil))
                                                      (reset! !edit-conversation false))
                                            :edit (let [new-topic (:changes @!edit-conversation)]
                                                    (e/server
                                                      (e/offload #(dh/transact !dh-conn [{:db/id [:conversation/id convo-id]
                                                                                          :conversation/topic new-topic}]))
                                                      nil)
                                                    (reset! !edit-conversation false)))))
                        (dom/on "click" (e/fn [_] (reset! !edit-conversation {:convo-id convo-id
                                                                              :action :edit})))))
                    (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                      (if editing?
                        (dom/on "click" (e/fn [_] (reset! !edit-conversation false)))
                        (dom/on "click" (e/fn [_] (reset! !edit-conversation {:convo-id convo-id
                                                                              :action :delete}))))
                      (set! (.-innerHTML dom/node) (if editing? x-icon delete-icon)))))))))))))

(e/defn FolderList [folders]
  (when (seq folders)
    (dom/div (dom/props {:class "flex border-b border-white/20 pb-2"})
      (dom/div (dom/props {:class "flex w-full flex-col pt-2"})
        (e/for [[_created eid folder-id name] folders]
          (let [editing? (= folder-id (:folder-id edit-folder))
                open-folder? (contains? open-folders folder-id)
                conversations (e/server (e/offload #(sort-by first > (dh/q '[:find ?created ?c ?c-id ?topic ?folder-name
                                                                            :in $ ?folder-id
                                                                            :where
                                                                            [?e :folder/id ?folder-id]
                                                                            [?e :folder/name ?folder-name]
                                                                            [?c :conversation/folder ?folder-id]
                                                                            [?c :conversation/id ?c-id]
                                                                            [?c :conversation/topic ?topic]
                                                                            [?c :conversation/created ?created]]
                                                                      db folder-id))))]
            (dom/div (dom/props {:class "relative flex items-center"})
              (if-not (and editing? (= :edit (:action edit-folder)))
                (dom/button (dom/props {:class (str (when (= folder-id folder-dragged-to) "bg-[#343541]/90 ") "flex w-full cursor-pointer items-center gap-3 rounded-lg p-3 text-sm transition-colors duration-200 hover:bg-[#343541]/90")})
                  (dom/on "click" (e/fn [_] (if-not open-folder?
                                              (swap! !open-folders conj folder-id)
                                              (swap! !open-folders disj folder-id))))
                  (dom/on "dragover" (e/fn [e] (.preventDefault e)))
                  (dom/on "dragenter" (e/fn [_] (.requestAnimationFrame js/window
                                                  #(reset! !folder-dragged-to folder-id))))
                  (dom/on "dragleave" (e/fn [_] (fn [_] (reset! !folder-dragged-to nil))))
                  (dom/on "drop" (e/fn [_]
                                   (let [convo-id @!convo-dragged]
                                     (e/server (e/offload #(dh/transact !dh-conn [{:db/id [:conversation/id convo-id]
                                                                                   :conversation/folder folder-id}]))
                                       nil))
                                   (swap! !open-folders conj folder-id)
                                   (reset! !folder-dragged-to nil)
                                   (reset! !convo-dragged nil)))
                  (dom/div
                    (set! (.-innerHTML dom/node) (if-not open-folder? folder-arrow-icon folder-arrow-icon-down)))
                  (dom/text name))
                (dom/div (dom/props {:class "flex w-full items-center gap-3 rounded-lg bg-[#343541]/90 p-3"})
                  (set! (.-innerHTML dom/node) folder-arrow-icon)
                  (dom/input (dom/props {:class "mr-12 flex-1 overflow-hidden overflow-ellipsis border-neutral-400 bg-transparent text-left text-[12.5px] leading-3 text-white outline-none focus:border-neutral-100"
                                         :value name})
                    (dom/on "keyup" (e/fn [e]
                                      (when-some [v (empty->nil (.. e -target -value))]
                                        (swap! !edit-folder assoc :changes v))))
                    (.focus dom/node))))
              (dom/div (dom/props {:class "absolute right-1 z-10 flex text-gray-300"})
                (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                  (if editing?
                    (dom/on "click" (e/fn [_]
                                      (case (:action edit-folder)
                                        :delete (e/server (e/offload #(dh/transact !dh-conn [[:db.fn/retractEntity eid]]))
                                                  nil)
                                        :edit (let [new-folder-name (:changes @!edit-folder)]
                                                (e/server
                                                  (e/offload #(dh/transact !dh-conn [{:db/id [:folder/id folder-id]
                                                                                      :folder/name new-folder-name}]))
                                                  nil)
                                                (reset! !edit-folder false)))))
                    (dom/on "click" (e/fn [_] (reset! !edit-folder {:folder-id folder-id
                                                                    :action :edit}))))
                  (set! (.-innerHTML dom/node) (if editing? tick-icon edit-icon)))
                (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                  (set! (.-innerHTML dom/node) (if editing? x-icon delete-icon))
                  (if editing?
                    (dom/on "click" (e/fn [_] (reset! !edit-folder nil)))
                    (dom/on "click" (e/fn [_] (reset! !edit-folder {:folder-id folder-id
                                                                    :action :delete})))))))
            (when (and (seq conversations) open-folder?)
              (ConversationList. conversations))))))))

(e/defn LeftSidebar []
  (dom/button (dom/props {:class (if-not sidebar?
                                   "transform scale-x-[-1] fixed top-2.5 left-2 z-50 h-7 w-7 text-white hover:text-gray-400 dark:text-white dark:hover:text-gray-300 sm:top-0.5 sm:left-2 sm:h-8 sm:w-8 sm:text-neutral-700 "
                                   "fixed top-5 left-[270px] z-50 h-7 w-7 hover:text-gray-400 dark:text-white dark:hover:text-gray-300 sm:top-0.5 sm:left-[270px] sm:h-8 sm:w-8 sm:text-neutral-700 text-white")})
    (dom/on "click" (e/fn [_]
                      (when (mobile-device?) (reset! !prompt-sidebar? false))
                      (reset! !sidebar? (not @!sidebar?))))
    (set! (.-innerHTML dom/node) side-bar-icon))
  (when sidebar?
    (let [folders (e/server  (e/offload #(sort-by first > (dh/q '[:find ?created ?e ?folder-id ?name
                                                                  :where
                                                                  [?e :folder/id ?folder-id]
                                                                  [?e :folder/name ?name]
                                                                  [?e :folder/created ?created]]
                                                            db))))
          !search-text (atom nil)
          search-text (e/watch !search-text)
          conversations  (if-not search-text
                           (e/server
                             (e/offload #(sort-by first > (dh/q '[:find ?created ?e ?conv-id ?topic
                                                                  :where
                                                                  [?e :conversation/id ?conv-id]
                                                                  [?e :conversation/topic ?topic]
                                                                  [?e :conversation/created ?created]
                                                                  (not [?e :conversation/folder])]
                                                            db))))

                           (e/server
                             (e/offload #(let [convo-eids (dh/q '[:find [?c ...]
                                                                  :in $ search-txt ?includes-fn
                                                                  :where
                                                                  [?m :message/text ?msg-text]
                                                                  [?c :conversation/messages ?m]
                                                                  [?c :conversation/topic ?topic]
                                                                  (or-join [?msg-text ?topic]
                                                                    [(?includes-fn ?msg-text search-txt)]
                                                                    [(?includes-fn ?topic search-txt)])]
                                                            db search-text lowercase-includes?)]
                                           (sort-by first > (dh/q '[:find ?created ?e ?conv-id ?topic
                                                                    :in $ [?e ...]
                                                                    :where
                                                                    [?e :conversation/id ?conv-id]
                                                                    [?e :conversation/topic ?topic]
                                                                    [?e :conversation/created ?created]]
                                                              db convo-eids))))))
          !clear-conversations? (atom false)
          clear-conversations? (e/watch !clear-conversations?)]
      (dom/div (dom/props {:class "fixed top-0 left-0 z-40 flex h-full w-[260px] flex-none flex-col space-y-2 bg-[#202123] p-2 text-[14px] transition-all sm:relative sm:top-0"})
        (dom/div (dom/props {:class "flex items-center"})
          (dom/button (dom/props {:class "text-sidebar flex w-[190px] flex-shrink-0 cursor-pointer select-none items-center gap-3 rounded-md border border-white/20 p-3 text-white transition-colors duration-200 hover:bg-gray-500/10"})
            (set! (.-innerHTML dom/node) new-chat-icon)
            (dom/on "click" (e/fn [_] (reset! !view-main :pre-conversation)))
            (dom/text "New Chat"))

          (dom/button (dom/props {:class "ml-2 flex flex-shrink-0 cursor-pointer items-center gap-3 rounded-md border border-white/20 p-3 text-sm text-white transition-colors duration-200 hover:bg-gray-500/10"})
            (dom/on "click" (e/fn [_]
                              (e/server
                                (e/offload #(dh/transact !dh-conn [{:folder/id (nano-id)
                                                                    :folder/name "New folder"
                                                                    :folder/created (System/currentTimeMillis)}]))
                                nil)))
            (set! (.-innerHTML dom/node) search-icon)))

        (dom/div (dom/props {:class "relative flex items-center"})
          (dom/input (dom/props {:class "w-full flex-1 rounded-md border border-neutral-600 bg-[#202123] px-4 py-3 pr-10 text-[14px] leading-3 text-white"
                                 :placeholder "Search..."
                                 :value search-text})
            (dom/on "keyup" (e/fn [e]
                              (if-some [v (empty->nil (.. e -target -value))]
                                (reset! !search-text v)
                                (reset! !search-text nil))))))
        (when search-text (dom/p (dom/props {:class "text-gray-500 text-center"})
                            (dom/text (str (count  conversations)) #_(map second conversations) " results found")))

             ;; Conversations
        (dom/div (dom/props {:class "flex-grow overflow-auto flex flex-col"})
          (if (or (seq conversations) (seq folders))
            (do
              (FolderList. folders)
              (ConversationList. conversations))
            (dom/div
              (dom/div (dom/props {:class "mt-8 select-none text-center text-white opacity-50"})
                (set! (.-innerHTML dom/node) no-data-icon)
                (dom/text "No Data")))))

        (dom/div (dom/props {:class "flex flex-col items-center space-y-1 border-t border-white/20 pt-1 text-sm"})
          (dom/button (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 text-white transition-colors duration-200 hover:bg-gray-500/10"})
            (set! (.-innerHTML dom/node) transfer-data-icon)
            (dom/on "click" (e/fn [_]
                              (reset! !view-main :data-export)
                              (when (mobile-device?) (reset! !sidebar? false))))
            (dom/text "Export"))
          (if-not clear-conversations?
            (dom/button (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 text-white transition-colors duration-200 hover:bg-gray-500/10"})
              (set! (.-innerHTML dom/node) delete-icon)
              (dom/on "click" (e/fn [_]
                                (println "clear conversations")
                                (reset! !clear-conversations? true)))
              (dom/text "Clear conversations"))
            (dom/div (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 text-white transition-colors duration-200 hover:bg-gray-500/10"})
              (set! (.-innerHTML dom/node) delete-icon)
              (dom/text "Are you sure?")
              (dom/div (dom/props {:class "absolute right-1 z-10 flex text-gray-300"})
                (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                  (set! (.-innerHTML dom/node) tick-icon)
                  (dom/on "click" (e/fn [_]
                                    (e/server
                                      (e/offload #(let [convo-eids (map :e (dh/datoms @!dh-conn :avet :conversation/id))
                                                        folder-eids (map :e (dh/datoms @!dh-conn :avet :folder/id))
                                                        retraction-ops (concat (map (fn [eid] [:db.fn/retractEntity eid]) convo-eids)
                                                                         (map (fn [eid] [:db.fn/retractEntity eid]) folder-eids))]
                                                    (dh/transact !dh-conn retraction-ops)))
                                      nil)
                                    (reset! !active-conversation nil)
                                    (reset! !clear-conversations? false))))
                (dom/button (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                  (dom/on "click" (e/fn [_] (reset! !clear-conversations? false)))
                  (set! (.-innerHTML dom/node) x-icon)))))
          (dom/button (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 text-white transition-colors duration-200 hover:bg-gray-500/10"})
            (set! (.-innerHTML dom/node) settings-icon)
            (dom/on "click" (e/fn [_]
                              (reset! !view-main :settings)
                              (when (mobile-device?) (reset! !sidebar? false))))
            (dom/text "Settings")))))))

(e/defn RightSidebar []
  (dom/button (dom/props {:class (if-not prompt-sidebar?
                                   "transform fixed top-2.5 right-2 z-50 h-7 w-7 text-white hover:text-gray-400 dark:text-white dark:hover:text-gray-300 sm:top-0.5 sm:right-2 sm:h-8 sm:w-8 sm:text-neutral-700 "
                                   "fixed top-5 scale-x-[-1] right-[270px] z-50 h-7 w-7 hover:text-gray-400 dark:text-white dark:hover:text-gray-300 sm:top-0.5 sm:right-[270px] sm:h-8 sm:w-8 sm:text-neutral-700 text-white")})
    (dom/on "click" (e/fn [_]
                      (when (mobile-device?) (reset! !sidebar? false))
                      (reset! !prompt-sidebar? (not @!prompt-sidebar?))))
    (set! (.-innerHTML dom/node) side-bar-icon))
  (when prompt-sidebar?
    (let [!search-prompts? (atom nil)
          search-prompts? (e/watch !search-prompts?)
          search-result-prompt-eid   (when  search-prompts?
                                       (e/server (e/offload #(dh/q '[:find [?c ...]
                                                                     :in $ search-txt ?includes-fn
                                                                     :where
                                                                     [?m :prompt/text ?prompt-text]
                                                                     [?c :prompt/name ?name]
                                                                     (or-join [?prompt-text ?name]
                                                                       [(?includes-fn ?prompt-text search-txt)]
                                                                       [(?includes-fn ?name search-txt)])]
                                                               db search-prompts? lowercase-includes?))))
          prompts (let [search? search-prompts?]
                    (e/server
                      (e/offload #(if search?
                                    (sort-by first > (dh/q '[:find ?created ?prompt-id ?name ?text ?e
                                                             :in $ [?e ...]
                                                             :where
                                                             [?e :prompt/id ?prompt-id]
                                                             [?e :prompt/name ?name]
                                                             [?e :prompt/created ?created]
                                                             [?e :prompt/text ?text]]
                                                       db search-result-prompt-eid))
                                    (sort-by first > (dh/q '[:find ?created ?prompt-id ?name ?text ?e
                                                             :where
                                                             [?e :prompt/id ?prompt-id]
                                                             [?e :prompt/name ?name]
                                                             [?e :prompt/created ?created]
                                                             [?e :prompt/text ?text]]
                                                       db))))))]
      (dom/div (dom/props {:class "fixed top-0 right-0 z-40 flex h-full w-[260px] flex-none flex-col space-y-2 bg-[#202123] p-2 text-[14px] transition-all sm:relative sm:top-0"})
        (dom/div (dom/props {:class "flex items-center"})
          (dom/button (dom/props {:class "text-sidebar flex w-full flex-shrink-0 cursor-pointer select-none items-center gap-3 rounded-md border border-white/20 p-3 text-white transition-colors duration-200 hover:bg-gray-500/10"})
            (set! (.-innerHTML dom/node) new-chat-icon)
            (dom/on "click" (e/fn [_]
                              (reset! !view-main :prompt-editor)
                              (reset! !prompt-editor {:action :create
                                                      :name nil
                                                      :text nil})))
            (dom/text "New Prompt")))

        (dom/div (dom/props {:class "relative flex items-center"})
          (dom/input (dom/props {:class "w-full flex-1 rounded-md border border-neutral-600 bg-[#202123] px-4 py-3 pr-10 text-[14px] leading-3 text-white"
                                 :placeholder "Search..."
                                 :value search-prompts?}))

          (dom/on "keyup" (e/fn [e]
                            (if-some [v (empty->nil (.. e -target -value))]
                              (reset! !search-prompts? v)
                              (reset! !search-prompts? nil)))))
        (when search-prompts? (dom/p (dom/props {:class "text-gray-500 text-center"})
                                (dom/text (str (count search-result-prompt-eid)) " results found")))

             ;; Prompts
        (dom/div (dom/props {:class "flex-grow overflow-auto flex flex-col"})
          (if (seq prompts)
            (do
              (dom/div (dom/props {:class "flex w-full flex-col pt-2 flex-grow"})
                (e/for [[created prompt-id name text eid] prompts]
                  (dom/button (dom/props {:class "flex w-full cursor-pointer items-center gap-3 rounded-lg p-3 text-sm transition-colors duration-200 hover:bg-[#343541]/90"
                                          :draggable true})
                    (set! (.-innerHTML dom/node) prompt-icon)
                    (dom/on "dragstart" (e/fn [_] (reset! !prompt-dragged prompt-id)))
                    (dom/on "click" (e/fn [_]
                                      (reset! !view-main :prompt-editor)
                                      (reset! !prompt-editor {:action :edit
                                                              :name name
                                                              :text text
                                                              :eid eid
                                                              :prompt-id prompt-id})))
                    (dom/text name)))))
            (dom/div
              (dom/div (dom/props {:class "mt-8 select-none text-center text-white opacity-50"})
                (set! (.-innerHTML dom/node) no-data-icon)
                (dom/text "No Data")))))))))

(e/defn PromptEditor []
  (e/client
    (dom/div (dom/props {:class "mx-auto flex flex-col space-y-5 md:space-y-10 px-3 pt-5 md:pt-12 sm:max-w-[600px]"})
      (dom/div (dom/props {:class "text-center text-3xl font-semibold text-gray-800 dark:text-gray-100"})
        (dom/text (case (:action prompt-editor)
                    :create "Create Prompt"
                    :edit "Edit Prompt")))
      (dom/div (dom/props {:class "flex h-full flex-col space-y-4 rounded-lg border border-neutral-200 p-4 dark:border-neutral-600"})
        (dom/div (dom/props {:class "flex flex-col"}))
        (dom/label (dom/text "Name"))
        (dom/input (dom/props {:class "w-full rounded-lg border border-neutral-200 bg-transparent px-4 py-3 text-neutral-900 dark:border-neutral-600 dark:text-neutral-100"
                               :placeholder "Prompt name..."
                               :value (:name prompt-editor)})
          (dom/on "keyup" (e/fn [e]
                            (if-some [v (empty->nil (.. e -target -value))]
                              (swap! !prompt-editor assoc :name v)
                              (swap! !prompt-editor assoc :name nil)))))
        (dom/label (dom/text "Prompt"))
        (dom/textarea (dom/props {:class "w-full rounded-lg border border-neutral-200 bg-transparent px-4 py-3 text-neutral-900 dark:border-neutral-600 dark:text-neutral-100 resize-none"
                                  :placeholder "Type your prompt here..."
                                  :value (:text prompt-editor)})
          (dom/on "keyup" (e/fn [e]
                            (if-some [v (empty->nil (.. e -target -value))]
                              (swap! !prompt-editor assoc :text v)
                              (swap! !prompt-editor assoc :text nil)))))
        (let [edit? (= :edit (:action prompt-editor))]
          (dom/div (dom/props {:class (str (if edit? "justify-between" "justify-end") " flex")})
            (when edit?
              (dom/button
                (dom/props {:class "border py-2 px-4 bg-red-800 rounded hover:bg-red-400 hover:text-black"})
                (dom/on "click" (e/fn [_]
                                  (let [eid (:eid prompt-editor)]
                                    (e/server (e/offload #(dh/transact !dh-conn [(dh/transact !dh-conn [[:db.fn/retractEntity eid]])]))
                                      nil))
                                  (reset! !view-main @!view-main-prev)
                                  (reset! !prompt-editor {:action nil
                                                          :name nil
                                                          :text nil})))
                (dom/text "Delete")))

            (dom/div (dom/props {:class "flex gap-4"})
              (dom/button
                (dom/props {:class "border py-2 px-4 bg-[#202123] rounded hover:bg-white hover:text-black"})
                (dom/on "click" (e/fn [_]
                                  (reset! !view-main @!view-main-prev)
                                  (reset! !prompt-editor {:action nil
                                                          :name nil
                                                          :text nil})))
                (dom/text "Cancel"))
              (dom/button
                (dom/props {:class "border py-2 px-4 bg-[#202123] rounded hover:bg-white hover:text-black"})
                (dom/on "click" (e/fn [_]
                                  (case (:action prompt-editor)
                                    :create (e/server (e/offload #(dh/transact !dh-conn [{:prompt/id (nano-id)
                                                                                          :prompt/name (:name prompt-editor)
                                                                                          :prompt/text (:text prompt-editor)
                                                                                          :prompt/created (System/currentTimeMillis)}]))
                                              nil)
                                    :edit (e/server (e/offload #(dh/transact !dh-conn [{:prompt/id (:prompt-id prompt-editor)
                                                                                        :prompt/name (:name prompt-editor)
                                                                                        :prompt/text (:text prompt-editor)
                                                                                        :prompt/edited (System/currentTimeMillis)}]))
                                            nil))
                                  (reset! !prompt-editor {:action nil
                                                          :name nil
                                                          :text nil})
                                  (reset! !view-main @!view-main-prev)))
                (dom/text (case (:action prompt-editor)
                            :create "Create Prompt"
                            :edit "Save"))))))))))


(e/defn Export []
  (let [folders (e/server  (e/offload #(sort-by first > (dh/q '[:find ?created ?e ?folder-id ?name
                                                               :where
                                                               [?e :folder/id ?folder-id]
                                                               [?e :folder/name ?name]
                                                               [?e :folder/created ?created]]
                                                         db))))
        conversations (e/server  (e/offload #(sort-by first > (dh/q '[:find ?created ?e ?conv-id ?topic
                                                                     :where
                                                                     [?e :conversation/id ?conv-id]
                                                                     [?e :conversation/topic ?topic]
                                                                     [?e :conversation/created ?created]]
                                                               db))))]

    (e/client
      (let [!active-tab (atom :folders)
            active-tab (e/watch !active-tab)
            !folders-selected (atom #{})
            folders-selected (e/watch !folders-selected)
            !conversations-selected (atom #{})
            conversations-selected (e/watch !conversations-selected)]
        (dom/div (dom/props {:class "mx-auto flex flex-col space-y-5 md:space-y-10 px-3 pt-5 md:pt-12 sm:max-w-[600px] min-h-64"})
          (dom/div (dom/props {:class "text-center text-3xl font-semibold text-gray-800 dark:text-gray-100"})
            (dom/text "Export"))
          (dom/div (dom/props {:class "flex h-full flex-col gap-8 rounded-lg border border-neutral-200 p-4 dark:border-neutral-600 justify-between"
                               :style {:min-height "444px"}})
            (dom/div
              (dom/div (dom/props {:class "flex justify-center items-center"})
                (dom/div (dom/props {:class "flex rounded-lg border border-neutral-200 w-content relative"})
                  (dom/div (dom/props {:class (str (if (= active-tab :folders) "translate-x-0" "translate-x-full") " transform transition-transform duration-200 ease-in absolute w-44 h-full bg-[#202123] border border-neutral-200 rounded-lg")}))
                  (dom/button (dom/props {:class "text-xl p-4 w-44 rounded-lg z-10"})
                    (dom/on "click" (e/fn [_] (reset! !active-tab :folders)))
                    (dom/text "Folders"))
                  (dom/button (dom/props {:class "text-xl p-4 w-44 rounded-lg z-10"})
                    (dom/on "click" (e/fn [_] (reset! !active-tab :conversations)))
                    (dom/text "Conversations"))))
              (when-not (seq conversations)
                (dom/div (dom/props {:class "mt-24 select-none text-center text-white opacity-50"})
                  (set! (.-innerHTML dom/node) no-data-icon)
                  (dom/text "Nothing to Export")))

              (when (and (not (seq folders))
                      (seq conversations)
                      (= active-tab :folders))
                (dom/div (dom/props {:class "mt-12 select-none text-center text-white opacity-50"})
                  (set! (.-innerHTML dom/node) no-data-icon)
                  (dom/text "No folders")))

              (case active-tab
                :folders (when (seq folders)
                           (reset! !conversations-selected #{})
                           (dom/ol (dom/props {:class "mt-12 ml-8"})
                             (doseq [[created e folder-id folder-name] folders]
                               (let [checked (or (contains? folders-selected folder-id))
                                     conversations? (e/server (e/offload #(seq (dh/q '[:find ?e
                                                                                       :in $ ?folder-id
                                                                                       :where
                                                                                       [?e :conversation/folder ?folder-id]]
                                                                                 db folder-id))))]
                                 (dom/li (dom/props {:class "flex items-center mb-2"})
                                   (ui/checkbox checked
                                     (e/fn [v]
                                       (if v
                                         (swap! !folders-selected conj folder-id)
                                         (swap! !folders-selected disj folder-id)))
                                     (dom/props {:id (str "folder-" folder-name)}))
                                   (dom/label (dom/props {:for (str "folder-" folder-name) 
                                                          :class "ml-2 flex gap-4"})
                                     (dom/p
                                       (dom/text folder-name))
                                     (when-not conversations?
                                       (dom/p (dom/props {:class "text-neutral-700 dark:text-neutral-400"})
                                         (dom/i
                                           (dom/text "empty"))))))))))
                :conversations (when (seq conversations)
                                 (reset! !folders-selected #{})
                                 (dom/ol (dom/props {:class "mt-12 ml-8"})
                                   (doseq [[_created _e conv-id conv] conversations]
                                     (let [checked? (contains? conversations-selected conv-id)]
                                       (dom/li (dom/props {:class "flex items-center mb-2"})
                                         (ui/checkbox checked?
                                           (e/fn [v]
                                             (if v
                                               (swap! !conversations-selected conj conv-id)
                                               (swap! !conversations-selected disj conv-id)))
                                           (dom/props {:id (str "conv-" conv)}))
                                         (dom/label (dom/props {:for (str "conv-" conv) :class "ml-2"})
                                           (dom/text (name conv))))))))))

            (dom/div (dom/props {:class "flex flex-col sm:flex-row gap-4 justify-end"})
              (dom/button
                (dom/props {:class "rounded border py-2 px-4 bg-[#202123] rounded hover:bg-white hover:text-black"})
                (dom/on "click" (e/fn [_] (reset! !view-main @!view-main-prev)))
                (dom/text "Cancel"))
              (when (and (seq conversations)
                      (or (seq folders-selected)
                        (seq conversations-selected)))
                (dom/button
                  (dom/props {:class "rounded border py-2 px-4 bg-[#202123] rounded hover:bg-white hover:text-black"})
                  (dom/on "click" (e/fn [_]
                                    (e/server
                                      (e/offload #(do (println "Exporting: " conversations-selected)
                                                    (clojure.pprint/pprint {#_:folders #_(->> (sort-by first > (dh/q '[:find ?created ?folder-id ?name
                                                                                                                       :in $ [?folder-id ...]
                                                                                                                       :where
                                                                                                                       [?e :folder/id ?folder-id]
                                                                                                                       [?e :folder/name ?name]
                                                                                                                       [?e :folder/created ?created]]
                                                                                                                 db folders-selected))
                                                                                           (mapv (fn [[created id name]]
                                                                                                   {:id id
                                                                                                    :created created
                                                                                                    :name name})))
                                                                            :conversations   (->> (dh/q '[:find ?convo-id ?topic ?created ?folder
                                                                                                          :in $ [?convo-id ...]
                                                                                                          :where
                                                                                                          [?e :conversation/id ?convo-id]
                                                                                                          [?e :conversation/topic ?topic]
                                                                                                          [?c :conversation/created ?created]
                                                                                                          [?c :conversation/folder ?folder]]
                                                                                                    db conversations-selected)
                                                                                               (mapv (fn [[convo-id topic created folder]]
                                                                                                       {:id convo-id
                                                                                                        :topic topic
                                                                                                        :created created
                                                                                                        :folder folder
                                                                                                        :messages   (mapv (fn [[msg-created msg-id msg-text msg-role]]
                                                                                                                            {:id msg-id
                                                                                                                             :created msg-created
                                                                                                                             :text msg-text
                                                                                                                             :role msg-role})
                                                                                                                      (fetch-convo-messages db convo-id))})))}))))))
                  (dom/text "Export Selected")))
              (when (seq conversations)
                (dom/button
                  (dom/props {:class "rounded border py-2 px-4 bg-[#202123] rounded hover:bg-white hover:text-black"})
                  (dom/on "click" (e/fn [_] (println "Exporting: ")))
                  (dom/text "Export All"))))))))))

(e/defn Main []
  (dom/div (dom/props {:class "flex flex-1 h-full w-full"})
    (dom/div (dom/props {:class "relative flex-1 overflow-hidden bg-white dark:bg-[#343541]"})
      (dom/div (dom/props {:class "max-h-full overflow-x-hidden"})
        (case view-main
          :pre-conversation (PreConversation.)
          :conversation (Conversation.)
          :prompt-editor (PromptEditor.)
          :data-export (Export.)
          :settings (Settings.))))))

(e/defn Chat-app []
  (e/server
    (binding [db (e/watch !dh-conn)]
      (e/client 
        (dom/main (dom/props {:class "flex h-full w-screen flex-col text-sm text-white dark:text-white dark"})
          (dom/div (dom/props {:class "flex h-full w-full pt-[48px] sm:pt-0 items-start"})
            (LeftSidebar.) 
            (Main.) 
            (RightSidebar.)))))))