(ns inbox-feed.core
  (:use [postal core]
        [hiccup core]
        [clojure.tools logging cli])
  (:import (java.net URL)
           (java.util Date)
           (java.io File InputStreamReader)
           (com.sun.syndication.feed.synd SyndFeed)
           (com.sun.syndication.io SyndFeedInput XmlReader))
  (:gen-class))

(def encoding (System/getProperty "file.encoding"))

(defn setup-logging []
  (let [logger (java.util.logging.Logger/getLogger "")]
    (doseq [handler (.getHandlers logger)]
      (. handler setFormatter
         (proxy [java.util.logging.Formatter] []
           (format [record]
             (str (Date. (.getMillis record)) " "
                  (.getLevel record) ": "
                  (.getMessage record) "\n")))))))

(defn set-log-level! [level]
  (let [^java.util.logging.LogManager$RootLogger logger (java.util.logging.Logger/getLogger "")]
    (.setLevel logger level)
    (doseq [^java.util.logging.Handler handler (.getHandlers logger)]
      (. handler setLevel level))))

(let [props (java.util.Properties.)
      session (delay (javax.mail.Session/getDefaultInstance props))
      conn (atom nil)
      creds (atom nil)
      connect (fn []
                (let [[protocol server user pass] @creds]
                  (swap! conn (fn [_]
                                (doto (.getStore @session protocol)
                                  (.connect server user pass))))))]
  
  (defn imap-conn
    ([protocol server user pass]
       (.setProperty props "mail.store.protocol" protocol)
       (swap! creds (fn [_] [protocol server user pass]))
       (connect))
    ([]
       (if (.isConnected @conn)
         @conn
         (do (info "Reconnecting to IMAP Server.")
             (.close @conn)
             (connect)))))

  (defn folder [path]
    (reduce (fn[h v]
              (.getFolder h v))
            (.getFolder (imap-conn) (first path))
            (rest path)))

  (defn message [feed-name subject body-text body-html]
    (let [address (javax.mail.internet.InternetAddress. (str feed-name " <inbox-feed@localhost>"))
          content (javax.mail.internet.MimeMultipart. "alternative")
          ;; text (doto (javax.mail.internet.MimeBodyPart.)
          ;;        (.setContent body-text (str "text/plain; charset=" encoding "")))
          html (doto (javax.mail.internet.MimeBodyPart.)
                 (.setContent body-html (str "text/html; charset=" encoding "")))]
      
      (doto content
        ;;(.addBodyPart text)
        (.addBodyPart html))
      
      (doto (javax.mail.internet.MimeMessage. @session)
        (.setSentDate (java.util.Date.))
        (.setFrom address)
        (.addRecipient javax.mail.Message$RecipientType/TO address)
        (.setSubject subject)
        (.setContent content))))

  (defn append-messages [folder messages]
    (when (not (empty? messages))
      (.open folder javax.mail.Folder/READ_WRITE)
      (.appendMessages folder (into-array messages))
      (.close folder false))))

(defn schedule-work
  ([f rate]
     (let [pool (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
       (.scheduleAtFixedRate
        pool f (long 0) (long rate) java.util.concurrent.TimeUnit/SECONDS)
       pool))
  ([jobs]
     (let [pool (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
       (doall (for [[f rate] jobs]
                (schedule-work pool f rate)))
       pool)))

(defn entry [e]
  {:author (.getAuthor e)
   :categories (into [] (map #(.getName %) (.getCategories e)))
   :content (cond (.getDescription e) (.getValue (.getDescription e))
                  (> (count (.getContents e)) 0) (.getValue (nth (.getContents e) 0))
                  :default "")
   :id (.getUri e)
   :link (.getLink e)
   :published (.getPublishedDate e)
   :title (.getTitle e)
   :updated (.getUpdatedDate e)})

(defn cleanup-invalid-cdata-chars [stream]
  (let [pattern (java.util.regex.Pattern/compile "[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD\uD800\uDC00-\uDBFF\uDFFF]")
        content (->> stream
                     java.io.InputStreamReader.
                     java.io.BufferedReader.
                     line-seq
                     (apply str))]
    (->> (.replaceAll (.matcher pattern content) "")
         .getBytes
         java.io.ByteArrayInputStream.)))

(defn open-connection [url]
  (let [url (URL. url)
        conn (.openConnection url)]
    (.setRequestProperty conn "User-Agent" "inbox-feed - http://nakkaya.com/inbox-feed.html")
    (.getInputStream conn)))

(defn parse [url]
  (let [input (SyndFeedInput.)
        feed (try (.build input (InputStreamReader.
                                 (open-connection url)))
                  (catch Exception e
                    (.build input (InputStreamReader.
                                   (cleanup-invalid-cdata-chars
                                    (open-connection url))))))]
    {:author (.getAuthor feed)
     :description (.getDescription feed)
     :language (.getLanguage feed)
     :link (.getLink feed)
     :type (.getFeedType feed)
     :published (.getPublishedDate feed)
     :title (.getTitle feed)
     :entries (map entry (.getEntries feed))}))

(defn diff-feed-entries [curr old]
  (reduce (fn[h v]
            (if (and (every? false? (map #(= (:id %) (:id v)) old))
                     (every? false? (map #(= (:title %) (:title v)) old)))
              (conj h v) h)) [] curr))

(defn feed-state [entries]
  (map #(dissoc % :author :categories :content :link :published :updated) entries))

(let [size 500]
  (defn fixed-size-seq [s xs]
    (cond (empty? xs) s
          (< (count s) size) (apply conj s xs)
          :default (let [excess (+ (- (count s) size) (count xs))]
                     (fixed-size-seq (take (- (count s) excess) s) xs)))))

(defn html-template [feed-name id title url content]
  (html [:html
         [:head
          [:meta {:http-equiv "Content-Type" :content (str "text/html; charset=" encoding "")}]]
         [:style {:type "text/css"}
          "a { color:#153aa4; text-decoration:underline; }
           pre, code { margin: 1.5em 0; white-space: pre; }
           pre, code, tt { font: 12px 'andale mono', 'monotype.com',
                          'lucida console', monospace; line-height: 1.5; }
           tt { display: block; margin: 1.5em 0; line-height: 1.5; }"]
         [:body {:style "padding:0; margin:0; background:#fff;"}
          [:div {:style "font-size: 20px; font-family: Arial,Helvetica,sans-serif;
                         letter-spacing: -1px; font-weight: bold;"}
           [:a {:href url :style "color: #153aa4; text-decoration:none;"} title]]
          [:div {:style "font-size: 12px; font-family: Arial, Helvetica, sans-serif; color: #575c5d;"}
           (.toString (java.util.Date.))]
          [:p {:style "font-size: 13px; font-family: Arial, Helvetica, sans-serif;
                       color: #404040; line-height:1.3em; padding-bottom:10px;" :bgcolor= "#ffffff"} content]
          [:p {:style "font-size: 12px; font-family: Arial, Helvetica, sans-serif; color: #FFFFFF;"} id]]]))

(defn mail-entry [creds feed-name feed-url entry id]
  (try
    (send-message
     (with-meta {:from (str feed-name " <" (:user creds) ">")
                 :to (:to creds)
                 :subject (:title entry)
                 :body (html-template
                        feed-name id (:title entry)
                        (:link entry) (:content entry))
                 :X-RSS-ID (if id
                             id feed-url)
                 :X-RSS-FEED feed-name}
       creds))
    (catch Exception e
      (log :debug "Message send failed retrying.")
      (mail-entry creds feed-name entry id))))

(defn inject-entries [feed-name id new-entries]
  (let [folder (if id
                 (folder id)
                 (folder ["INBOX"]))]
    
    (append-messages folder (map #(message feed-name
                                           (:title %)
                                           (str (:title %) "\n"
                                                (.toString (java.util.Date.)) "\n"
                                                (:link %) "\n")
                                           (html-template
                                            name nil (:title %) (:link %) (:content %)))
                                 new-entries))))

(let [imap-lock (Object.)] ;;gmail does not like multiple connections
  (defn watch-feed [state config url name id]
    (try
      (log :debug (str "Checking " url))
      
      (let [old-state (state url)
            feed (parse url)
            name (if (nil? name)
                   (:title feed) name)
            curr-state (:entries feed)
            new-entries (diff-feed-entries curr-state old-state)]

        (if (:imap-creds config)
          (locking imap-lock
            (inject-entries name id new-entries))
          (doseq [entry new-entries]
            (future (mail-entry (:smtp-creds config) name url entry id))))

        (dosync (alter state assoc url (fixed-size-seq old-state (feed-state new-entries)))))
      (catch Exception e
        (warn (str "Error checking " url " " e))))))

(defn watch-feeds [state config]
  (doseq [[feed freq id name] (:feed-list config)]
    (schedule-work #(watch-feed state config feed name id) (* (if (nil? freq)
                                                                60 freq) 60))))

(defn discard-feeds [state config]
  (doseq [[url] (:feed-list config)]
    (let [feed (parse url)
          old-state (state url)
          curr-state (feed-state (:entries feed))
          new-entries (diff-feed-entries curr-state old-state)]
      (dosync (alter state assoc url (fixed-size-seq old-state (feed-state new-entries)))))))

(defn prepare-config [file]
  (try
    (let [config (apply hash-map (read-string (slurp (File. file))))]
      (if (nil? (->> config :smtp-creds :user))
        (assoc-in config [:smtp-creds :user] "inbox-feed@localhost")
        config))
    (catch Exception e
      (warn (str "Error reading config file. " e))
      (System/exit 1))))

(defn prepare-state [file]
  (ref
   (if (.exists (File. file))
     (try (read-string (slurp file))
          (catch Exception e
            (warn "Error reading feed data.")
            {}))
     {})))

(def writer-agent (agent true))

(defn atomic-dump [_ obj feed-data]
  (try
    (let [data-file (File. feed-data)
          tmp-file (File. (str feed-data ".tmp"))
          writer (java.io.FileWriter. tmp-file)]
      (binding [*out* writer]
        (prn obj))
      (.close writer)
      (.renameTo tmp-file data-file))
    true
    (catch Exception e
      (warn e)
      true)))

(defn -main [& args]
  (let [[opts _ banner] (cli args
                             ["--verbose" "Verbose mode" :default false :flag true]
                             ["--config" "Config file location" :default "./config.clj"]
                             ["--data" "Feeds data file" :default "./feeds.data"]
                             ["--discard" "Do not send current feed content" :default false :flag true]
                             ["--help" "Show help" :default false :flag true])
        {:keys [verbose config data discard help]} opts]

    (when help
      (println "Inbox Feed")
      (println banner)
      (System/exit 0))

    (setup-logging)
    (when verbose
      (set-log-level! java.util.logging.Level/ALL))

    (let [config (prepare-config config)
          state (prepare-state data)]

      (info (str "Using " encoding " encoding."))

      (when (:imap-creds config)
        (info "Connecting to IMAP Server.")
        (apply imap-conn (:imap-creds config)))
      
      (add-watch state "save-state" (fn [k r o n]
                                      (send writer-agent atomic-dump n data)))

      (when discard
        (info "Discarding current feed content.")
        (discard-feeds state config))

      (watch-feeds state config))))

(comment
  (watch-feeds sample-config)

  (let [config (prepare-config "config.clj")
        state (prepare-state "feeds.data")]
    ;;(watch-feeds state config)

    (discard-feeds state config)
    
    ;; (let [[feed freq name id] (nth (:feed-list config) 5)]
    ;;   (watch-feed state config feed name id)
    ;;   :t
    ;;   (println [feed freq name id])
    ;;   )
    )

  )