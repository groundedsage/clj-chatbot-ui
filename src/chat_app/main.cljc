(ns chat-app.main
  (:require contrib.str
            #?(:clj [datahike.api :as d])
            #?(:cljs [goog.userAgent :as ua])
            #?(:cljs [goog.labs.userAgent.platform :as platform])
            #?(:clj [nextjournal.markdown :as md])
            #?(:clj [nextjournal.markdown.transform :as md.transform])
            #?(:clj [hiccup2.core :as h])
            #?(:clj [markdown.core :as md2])
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

(e/def entities-cfg (e/server (read-string (slurp "config.edn"))))

#?(:clj (def cfg {:store {:backend :mem :id "schemaless"}
                  :schema-flexibility :read}))

#?(:clj (defonce create-db (when-not (d/database-exists? cfg) (d/create-database cfg))))
#?(:clj (defonce !dh-conn (d/connect cfg)))
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
#?(:clj (defonce dh-schema-tx (d/transact !dh-conn {:tx-data dh-schema})))

#?(:cljs (defonce !view-main (atom :entity-selection)))
#?(:cljs (defonce !conversation-entity (atom nil)))
#?(:cljs (defonce !view-main-prev (atom nil)))
#?(:cljs (defonce view-main-watcher (add-watch !view-main :main-prev (fn [_k _r os _ns]
                                                                       (println "this is os: " os)
                                                                       (when-not (= os :settings))
                                                                         (reset! !view-main-prev os)))))

#?(:cljs (defonce !edit-folder (atom nil)))
#?(:cljs (defonce !active-conversation (atom nil)))
#?(:cljs (defonce !convo-dragged (atom nil)))
#?(:cljs (defonce !prompt-dragged (atom nil)))
#?(:cljs (defonce !folder-dragged-to (atom nil)))
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
(defonce !debug? (atom true))
(e/def debug? (e/client (e/watch !debug?)))

(e/def open-folders (e/client (e/watch !open-folders)))
(e/def view-main (e/client (e/watch !view-main)))
(e/def conversation-entity (e/client (e/watch !conversation-entity)))
(e/def edit-folder (e/client (e/watch !edit-folder)))
(e/def active-conversation (e/client (e/watch !active-conversation)))
(e/def convo-dragged (e/client (e/watch !convo-dragged)))
(e/def prompt-dragged (e/client (e/watch !prompt-dragged)))
(e/def folder-dragged-to (e/client (e/watch !folder-dragged-to)))
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
           (sort-by first < (d/q '[:find ?msg-created ?msg-id ?msg-text ?msg-role
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
                  (d/transact !dh-conn [{:conversation/id convo-id
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
  (e/client
  ;; TODO: add the system prompt to the message list
    (let [api-key (e/server (e/offload #(d/q '[:find ?v .
                                               :where
                                               [?e :active-key-name ?name]
                                               [?k :key/name ?name]
                                               [?k :key/value ?v]] db)))
          !input-node (atom nil) 
          ]
      (dom/div (dom/props {:class (str (if (mobile-device?) "bottom-8" "bottom-0") " absolute left-0 w-full border-transparent bg-gradient-to-b from-transparent via-white to-white pt-6 dark:border-white/20 dark:via-[#343541] dark:to-[#343541] md:pt-2")})
        (dom/div (dom/props {:class "stretch mx-2 mt-4 flex flex-row gap-3 last:mb-2 md:mx-4 md:mt-[52px] md:last:mb-6 lg:mx-auto lg:max-w-3xl"})
          (dom/div (dom/props {:class "flex flex-col w-full gap-2"}) 
            (dom/div (dom/props {:class "relative flex w-full flex-grow flex-col rounded-md border border-black/10 bg-white shadow-[0_0_10px_rgba(0,0,0,0.10)] dark:border-gray-900/50 dark:bg-[#40414F] dark:text-white dark:shadow-[0_0_15px_rgba(0,0,0,0.10)] sm:mx-4"})
              (dom/textarea (dom/props {:id "prompt-input"
                                        :class "sm:h-11 m-0 w-full resize-none border-0 bg-transparent p-0 py-2 pr-8 pl-10 text-black dark:bg-transparent dark:text-white md:py-3 md:pl-10"
                                        :placeholder (str "Message " conversation-entity)
                                        :value ""}) 
                (reset! !input-node dom/node)
                (dom/on "keydown" (e/fn [e]
                                    (when (= "Enter" (.-key e))
                                      (.preventDefault e)
                                      (when-some [v (empty->nil (.. e -target -value))]
                                        (when-not (str/blank? v)
                                         
                                          (if-not @!active-conversation
                                            (let [convo-id (nano-id)
                                                  ;; sys-prompt @!system-prompt
                                                  ]
                                              (reset! !active-conversation convo-id)
                                              (reset! !view-main :conversation)
                                              (e/server (let [time-point (System/currentTimeMillis)
                                                              model selected-model
                                                              temp temperature
                                                              message-list [{:role "system"
                                                                             :content "sys-prompt"}
                                                                            {:role "user"
                                                                             :content v}]
                                                              v-str v ; TODO: figure out why this needs to be done. Seems to be breaking without it.
                                                              ]
                                                          #_(stream-chat-completion convo-id message-list model api-key)
                                                          (e/offload #(do (d/transact !dh-conn [{:conversation/id convo-id
                                                                                                 :conversation/topic v
                                                                                                 :conversation/created time-point
                                                                                                 :conversation/system-prompt "sys-prompt"
                                                                                                 :conversation/messages [{:message/id (nano-id)
                                                                                                                          :message/text "sys-prompt"
                                                                                                                          :message/role :system
                                                                                                                          :message/created time-point}
                                                                                                                         {:message/id (nano-id)
                                                                                                                          :message/text v-str
                                                                                                                          :message/role :user
                                                                                                                          :message/created time-point}]}])
                                                                        nil)))
                                                nil)) 

                                            ;; Add messages to an existing conversation 
                                            (e/server 
                                              (let [;{:keys [db/id conversation/model]} (e/offload #(d/pull @!dh-conn '[:db/id :conversation/model] [:conversation/id convo-id]))
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
                                                    (e/offload #(do (d/transact !dh-conn [{:conversation/id convo-id
                                                                                           :conversation/messages new-message}])
                                                                  nil)) 

                                                    #_(e/offload #(try (d/transact !dh-conn [{:conversation/id convo-id
                                                                                              :conversation/messages new-message}])
                                                                    (catch Exception e
                                                                      (println "Caught exception " e))))
                                                    #_(stream-chat-completion convo-id message-list model api-key))))
                                              nil))))
                                              (set! (.-value @!input-node) "")
                                              ))))
              (ui/button (e/fn [] (set! (.-value @!input-node) ""))
                (dom/props {:class "absolute right-2 top-2 rounded-sm p-1 text-neutral-800 opacity-60 hover:bg-neutral-200 hover:text-neutral-900 dark:bg-opacity-50 dark:text-neutral-100 dark:hover:text-neutral-200"})
                (set! (.-innerHTML dom/node) send-icon)))))))))

(e/defn BotMsg [msg]
  (e/client
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
            (set! (.-innerHTML dom/node) delete-icon)))))))

(e/defn UserMsg [msg]
  (e/client 
    (dom/div (dom/props {:class "flex gap-2 self-end items-center"}) 
      (let [msg-hovered? (dom/Hovered?.)]
        (ui/button (e/fn [])
          (dom/props {:class (str "hover:bg-slate-500 rounded-full flex justify-center items-center w-8 h-8"
                               (if-not msg-hovered? 
                                 " invisible"
                                 " visible"))
                      :title "Edit message"})
          (set! (.-innerHTML dom/node) edit-icon)) 

        (dom/p (dom/props {:class "bg-slate-500 px-4 py-2 rounded-full"})
          (dom/text msg))))))

(e/defn RenderMsg [msg]
  (e/client
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
                              (set! (.-innerHTML dom/node) delete-icon)))))))))

(e/defn PreConversation []
  (e/client
    (let [entity (first (filter #(= (:name %) conversation-entity) (:entities entities-cfg)))
          {:keys [prompt image full-name name]} entity]
      (dom/div (dom/props {:class "flex flex-col stretch justify-center items-center h-full lg:max-w-3xl mx-auto gap-4"})
        (dom/div (dom/props {:class "flex flex-col gap-8 items-center"})
          (dom/img (dom/props {:class "w-48 mx-auto rounded-full"
                               :src image}))
          (dom/h1 (dom/props {:class "text-2xl"}) (dom/text (or full-name name)))
          (dom/p (dom/text "Pre conversation"))
          ;; Uncomment to check prompt
          #_(dom/p (dom/text (e/server (slurp (clojure.java.io/resource prompt)))))) 

        (PromptInput. {:convo-id (nano-id)
                       :messages nil #_messages})))))

(e/defn Conversation []
  (e/client
    (let [convo-id active-conversation
          [model temp convo-system-prompt] (when convo-id
                                             (e/server 
                                               (e/offload #(d/q '[:find [#_?model #_?temp ?system-prompt]
                                                                  :in $ ?conv-id
                                                                  :where
                                                                  [?e :conversation/id ?conv-id]
                                                                            ;;  [?e :conversation/model ?model]
                                                                            ;;  [?e :conversation/temp ?temp]
                                                                  [?e :conversation/system-prompt ?system-prompt]]
                                                             db convo-id))))
          
          messages (e/server (e/offload #(fetch-convo-messages db convo-id)))
          entity (first (filter #(= (:name %) conversation-entity) (:entities entities-cfg)))
          {:keys [prompt image full-name name]} entity]
      (dom/div (dom/props {:class "flex flex-col stretch justify-center items-center h-full lg:max-w-3xl mx-auto gap-4"})
        (dom/div (dom/props {:class "flex flex-col gap-8 items-center"})
          (dom/img (dom/props {:class "w-48 mx-auto rounded-full"
                               :src image}))
          (dom/h1 (dom/props {:class "text-2xl"}) (dom/text (or full-name name))) 
          ;; Uncomment to check prompt
          #_(dom/p (dom/text (e/server (slurp (clojure.java.io/resource prompt)))))) 
        (when messages ;todo: check if this is still needed
          (e/for [msg messages]
            (RenderMsg. msg)))
        #_(when (:streaming (get stream-msgs convo-id))
            (when-let [content (:content (get stream-msgs convo-id))]
              (BotMsg. content)))

        (PromptInput. {:convo-id convo-id
                       :messages nil #_messages})))))

(e/defn ConversationList [conversations]
  (e/client
    (let [inside-folder-atom? (atom false)
          inside-folder? (e/watch inside-folder-atom?)
          !edit-conversation (atom false)
          edit-conversation (e/watch !edit-conversation)]
      (dom/div (dom/props {:class "pt-2 flex-grow"})
        (dom/on "dragover" (e/fn [e] (.preventDefault e)))
        (dom/on "dragenter" (e/fn [_]
                              #_(.requestAnimationFrame js/window
                                  #(reset! !folder-dragged-to :default))))
        (dom/on "dragleave" (e/fn [_] (fn [_] (reset! !folder-dragged-to nil))))
        (dom/on "drop" (e/fn [_]
                          (println "drop ")
                         (let [convo-id @!convo-dragged]
                           (println "convo-id: " convo-id)
                           (e/server
                             (e/offload
                               #(do
                                  (println "Dropped convo-id on default: " convo-id)
                                  (when-let [eid (d/q '[:find ?e .
                                                         :in $ ?convo-id
                                                         :where
                                                         [?e :conversation/id ?convo-id]
                                                         [?e :conversation/folder]]
                                                   db convo-id)]
                                    (println "the folder: " eid)
                                    (d/transact !dh-conn [[:db/retract eid :conversation/folder]])))))
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
                      (dom/on "dragstart" (e/fn [_] 
                                            (println "setting convo-dragged: " convo-id)
                                            (reset! !convo-dragged convo-id)
                                            (println "set convo-dragged: " @!convo-dragged))) 
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
                                                    (e/offload #(d/transact !dh-conn [{:db/id [:conversation/id convo-id]
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
                                                        (e/server 
                                                          (e/offload #(d/transact !dh-conn [[:db/retract eid :conversation/id]])) 
                                                          nil)
                                                        (when (= convo-id @!active-conversation)
                                                          (reset! !active-conversation nil))
                                                        (reset! !edit-conversation false))
                                              :edit (let [new-topic (:changes @!edit-conversation)]
                                                      (e/server
                                                        (e/offload #(d/transact !dh-conn [{:db/id [:conversation/id convo-id]
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
                        (set! (.-innerHTML dom/node) (if editing? x-icon delete-icon))))))))))))))

(e/defn FolderList [folders]
  (e/client
    (when (seq folders)
      (dom/div (dom/props {:class "flex border-b border-white/20 pb-2"})
        (dom/div (dom/props {:class "flex w-full flex-col pt-2"})
          (e/for [[_created eid folder-id name] folders]
            (let [editing? (= folder-id (:folder-id edit-folder))
                  open-folder? (contains? open-folders folder-id)
                  conversations (e/server (e/offload #(sort-by first > (d/q '[:find ?created ?c ?c-id ?topic ?folder-name
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
                    (dom/on "dragenter" (e/fn [_] 
                                          (.requestAnimationFrame js/window
                                            (fn []
                                               (println "drag enter 1: " @!convo-dragged)
                                               (println "folder-id 1: " folder-id)
                                               (reset! !folder-dragged-to folder-id)))))
                    #_(dom/on "dragleave" (e/fn [_]
                                          (reset! !folder-dragged-to nil)))
                    (dom/on "drop" (e/fn [_]
                                     (println "drop " )
                                     #_(let [convo-id @!convo-dragged]
                                       (e/server (e/offload #(d/transact !dh-conn [{:db/id [:conversation/id convo-id]
                                                                                     :conversation/folder folder-id}]))
                                         nil))
                                     #_(swap! !open-folders conj folder-id)
                                     #_(reset! !folder-dragged-to nil)
                                     #_(reset! !convo-dragged nil)))
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
                                          :delete (e/server (e/offload #(d/transact !dh-conn [[:db.fn/retractEntity eid]]))
                                                    nil)
                                          :edit (let [new-folder-name (:changes @!edit-folder)]
                                                  (e/server
                                                    (e/offload #(d/transact !dh-conn [{:db/id [:folder/id folder-id]
                                                                                        :folder/name new-folder-name}]))
                                                    nil)
                                                  (reset! !edit-folder false)))))
                      (dom/on "click" (e/fn [_] (reset! !edit-folder {:folder-id folder-id
                                                                      :action :edit}))))
                    (set! (.-innerHTML dom/node) (if editing? tick-icon edit-icon)))
                  (ui/button
                    (e/fn []
                      (if editing?
                        (reset! !edit-folder nil)
                        (reset! !edit-folder {:folder-id folder-id
                                              :action :delete})))
                    (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                    (set! (.-innerHTML dom/node) (if editing? x-icon delete-icon)))))
              (when (and (seq conversations) open-folder?)
                (ConversationList. conversations)))))))))

(e/defn LeftSidebar []
  (e/client
    (ui/button
      (e/fn []
        (when (mobile-device?) (reset! !prompt-sidebar? false))
        (reset! !sidebar? (not @!sidebar?)))
      (dom/props {:class (if-not sidebar?
                           "transform scale-x-[-1] fixed top-2.5 left-2 z-50 h-7 w-7 text-white hover:text-gray-400 dark:text-white dark:hover:text-gray-300 sm:top-0.5 sm:left-2 sm:h-8 sm:w-8 sm:text-neutral-700 "
                           "fixed top-5 left-[270px] z-50 h-7 w-7 hover:text-gray-400 dark:text-white dark:hover:text-gray-300 sm:top-0.5 sm:left-[270px] sm:h-8 sm:w-8 sm:text-neutral-700 text-white")})
      (set! (.-innerHTML dom/node) side-bar-icon)) 
    (when sidebar?
      (let [folders (e/server  (e/offload #(sort-by first > (d/q '[:find ?created ?e ?folder-id ?name
                                                                    :where
                                                                    [?e :folder/id ?folder-id]
                                                                    [?e :folder/name ?name]
                                                                    [?e :folder/created ?created]]
                                                              db))))
            !search-text (atom nil)
            search-text (e/watch !search-text)
            conversations  (if-not search-text
                             (e/server
                               (e/offload #(sort-by first > (d/q '[:find ?created ?e ?conv-id ?topic
                                                                    :where
                                                                    [?e :conversation/id ?conv-id]
                                                                    [?e :conversation/topic ?topic]
                                                                    [?e :conversation/created ?created]
                                                                    (not [?e :conversation/folder])]
                                                              db))))

                             (e/server
                               (e/offload #(let [convo-eids (d/q '[:find [?c ...]
                                                                    :in $ search-txt ?includes-fn
                                                                    :where
                                                                    [?m :message/text ?msg-text]
                                                                    [?c :conversation/messages ?m]
                                                                    [?c :conversation/topic ?topic]
                                                                    (or-join [?msg-text ?topic]
                                                                      [(?includes-fn ?msg-text search-txt)]
                                                                      [(?includes-fn ?topic search-txt)])]
                                                              db search-text lowercase-includes?)]
                                             (sort-by first > (d/q '[:find ?created ?e ?conv-id ?topic
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
              (dom/on "click" (e/fn [_] (reset! !view-main :entity-selection)))
              (dom/text "New Chat")) 
            (ui/button
              (e/fn []
                (e/server
                  (e/offload #(d/transact !dh-conn [{:folder/id (nano-id)
                                                     :folder/name "New folder"
                                                     :folder/created (System/currentTimeMillis)}]))
                  nil))
              (dom/props {:class "ml-2 flex flex-shrink-0 cursor-pointer items-center gap-3 rounded-md border border-white/20 p-3 text-sm text-white transition-colors duration-200 hover:bg-gray-500/10"})
              (set! (.-innerHTML dom/node) search-icon)))

          (ui/button
            (e/fn []
              (reset! !active-conversation nil)
              (reset! !view-main :pre-conversation)) 
            (let [entity (first (:entities entities-cfg))]
              (dom/div (dom/props {:class "text-neutral-400 hover:text-neutral-100 hover:bg-[#343541]/90 flex items-center gap-4 py-2 px-4 rounded"})
                (dom/img (dom/props {:class "w-8 rounded-full"
                                     :src (:image entity)}))
                (dom/p (dom/text (:name entity))))))
          (ui/button
            (e/fn [] 
              (reset! !view-main :entity-selection)
              (reset! !active-conversation nil))
            (dom/div (dom/props {:class "text-neutral-400 hover:text-neutral-100 hover:bg-[#343541]/90 flex items-center gap-4 py-2 px-4 rounded"})
              (dom/img (dom/props {:class "w-8 rounded-full"
                                   :src (:all-entities-image entities-cfg)}))
              (dom/p (dom/text "All Entities"))))

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
            (if-not clear-conversations?
              (ui/button
                (e/fn []
                  (println "clear conversations")
                  (reset! !clear-conversations? true))
                (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 text-white transition-colors duration-200 hover:bg-gray-500/10"})
                (dom/text "Clear conversations"))
              (dom/div (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 text-white transition-colors duration-200 hover:bg-gray-500/10"})
                (set! (.-innerHTML dom/node) delete-icon)
                (dom/text "Are you sure?")
                (dom/div (dom/props {:class "absolute right-1 z-10 flex text-gray-300"})
                  (ui/button (e/fn []
                               (println "clearing all conversations")
                               (e/server
                                 (println "serverside call")
                                 (e/offload
                                   #(let [convo-eids (map :e (d/datoms @!dh-conn :avet :conversation/id))
                                          folder-eids (map :e (d/datoms @!dh-conn :avet :folder/id))
                                          retraction-ops
                                          (concat
                                            (mapv (fn [eid] [:db.fn/retractEntity eid :conversation/id]) convo-eids)
                                            (mapv (fn [eid] [:db.fn/retractEntity eid :folder/id]) folder-eids))]
                                      (d/transact !dh-conn retraction-ops))) 
                                 nil)
                               (reset! !active-conversation nil)
                               (reset! !clear-conversations? false))
                    (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                    (set! (.-innerHTML dom/node) tick-icon))

                  (ui/button (e/fn [] (reset! !clear-conversations? false))
                    (dom/props {:class "min-w-[20px] p-1 text-neutral-400 hover:text-neutral-100"})
                    (set! (.-innerHTML dom/node) x-icon)))))
            #_(ui/button (e/fn [] 
                           (reset! !view-main :settings)
                           (when (mobile-device?) (reset! !sidebar? false)))
                (dom/props {:class "flex w-full cursor-pointer select-none items-center gap-3 rounded-md py-3 px-3 text-[14px] leading-3 text-white transition-colors duration-200 hover:bg-gray-500/10"})
                (set! (.-innerHTML dom/node) settings-icon)
                (dom/text "Settings"))))))))

(e/defn TreeView [db-data]
  (e/client 
    (dom/div (dom/props {:class "p-4 h-full overflow-auto"}) 
      (let [!expanded-views (atom #{})
            expanded-views (e/watch !expanded-views)]
        (e/for-by identity [[k v] db-data]
          (let [expanded? (contains? expanded-views k)]
            (dom/div (dom/props {:class "cursor-pointer"})
              (dom/on "click" (e/fn [_]
                                (if-not expanded?
                                  (swap! !expanded-views conj k)
                                  (swap! !expanded-views disj k))))
              (dom/div (dom/props {:class "flex px-2 -mx-2 font-bold rounded"})
                (dom/p (dom/props {:class "w-4"})
                  (dom/text (if expanded? "▼" "▶")))
                (dom/p (dom/text k "  (count " (count (filter #(not (= :db/txInstant (:a %))) v)) ")" )))
              (when expanded?
                (dom/div (dom/props {:class "pl-4"})
                  (e/for-by identity [[k v] (group-by :e v)]
                    (e/for-by identity [{:keys [e a v t asserted]} (filter #(not (= :db/txInstant (:a %))) v)]
                      ;; (let [{:keys [e a v t]} v])
                      (dom/div (dom/props {:class "flex gap-4"})
                        (dom/p (dom/props {:class "w-4"})
                          (dom/text e))
                        (dom/p (dom/props {:class "w-1/3"})
                          (dom/text a))
                        (dom/p (dom/props {:class "w-1/3 text-ellipsis overflow-hidden"})
                          (dom/text v)) 
                        (dom/p (dom/props {:class "w-8"})
                          (dom/text asserted))))))))))))))




(e/defn DBInspector []
  (e/server
    (let [group-by-tx (fn [results] (reduce (fn [acc [e a v tx asserted]]
                                              (update acc tx conj {:e e :a a :v v :asserted asserted}))
                                      {}
                                      results))
          db-data (let [results (d/q '[:find ?e ?a ?v ?tx ?asserted
                                       :where
                                       [?e ?a ?v ?tx ?asserted]] (d/history db))]
                    (reverse (sort (group-by-tx results))))]
      (e/client
        (dom/div (dom/props {:class "absolute top-0 right-0 h-48 w-1/2 bg-red-500 overflow-auto"}) 
          (dom/p (dom/text "Active conversation: " active-conversation))
          (dom/p (dom/text "View main: " view-main))
          (dom/p (dom/text "Convo dragged: " convo-dragged))
          (dom/p (dom/text "Folder dragged to : " folder-dragged-to))
          (TreeView. db-data))))))

(e/defn EntitySelector []
  (e/client 
    (let [EntityCard (e/fn [title img-src]
                       (ui/button (e/fn [] 
                                    (reset! !view-main :pre-conversation)
                                    (reset! !conversation-entity title))
                         (dom/props {:class "flex flex-col gap-4 items-center bg-[#202123] hover:scale-110 hover:shadow-lg shadow rounded p-4 transition-all ease-in duration-150"})
                         (dom/img (dom/props {:class "w-48 mx-auto rounded"
                                              :src img-src}))
                         (dom/p (dom/props {:class "text-bold text-lg"})
                           (dom/text title))))] 
      (dom/div (dom/props {:class "flex justify-center items-center h-full gap-8"}) 
        (e/for-by identity [{:keys [name image]} (:entities entities-cfg)]
          (EntityCard. name image))))))

(e/defn MainView []
  (e/client
    (dom/div (dom/props {:class "flex flex-1 h-full w-full"})
      (dom/on "drop" (e/fn [_] (println "drop ")))
      (dom/on "dragdrop" (e/fn [_] (println "drop "))) 
      (dom/on "dragenter" (e/fn [_] (println "enter main")))
      (dom/on "dragleave" (e/fn [_] (println "leave main")))
      (dom/div (dom/props {:class "relative flex-1 overflow-hidden bg-white dark:bg-[#343541]"})
        (dom/div (dom/props {:class "max-h-full overflow-x-hidden h-full"})
          (case view-main
            :entity-selection (EntitySelector.)
            :pre-conversation (PreConversation.)
            :conversation (Conversation.))
          (when debug? (DBInspector.)))))))

(e/defn DebugController []
(e/client
  (ui/button 
    (e/fn [] (swap! !debug? not))
    (dom/props {:class (str "absolute top-0 right-0 z-10 px-4 py-2 rounded text-black"
                         (if-not debug? 
                           " bg-slate-500"
                           " bg-red-500"))})
    (dom/p (dom/text "Debug: " debug?)))))

(e/defn Main [ring-request]
  (e/server
    (binding [db (e/watch !dh-conn)]
      (e/client
        (binding [dom/node js/document.body] 
          (dom/main (dom/props {:class "flex h-full w-screen flex-col text-sm text-white dark:text-white dark"}) 
            (dom/div (dom/props {:class "flex h-full w-full pt-[48px] sm:pt-0 items-start"})
              (LeftSidebar.)
              (MainView.)
              (DebugController.))))))))
