(ns hive-html.page
  "A fetched HTML page as text, headings, links and metadata.

   Three ports, each with a JDK-only default: IHtmlStructureParser walks the
   markup into blocks (one paragraph per block, headings as markdown heading
   lines, pre blocks fenced), IHtmlTextExtractor reduces that to text, and
   IWebsiteMetadataParser reads title, description and canonical URL.
   IWebsiteParser composes them and is the port a consumer injects at.

   Rationale: hive memory 20260906010127-029f8f5e."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r])
  (:import [java.io StringReader]
           [javax.swing.text MutableAttributeSet]
           [javax.swing.text.html HTMLEditorKit$ParserCallback]
           [javax.swing.text.html.parser ParserDelegator]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defprotocol IHtmlTextExtractor
  (extract-text [this html]))

(def ^:private boilerplate-tags
  #{"head" "script" "style" "noscript" "svg" "nav" "header" "footer" "aside" "form"})

(def ^:private boilerplate-fragments
  "Substrings that, anywhere in a node's tag/id/class/role, mark it chrome."
  ["advert" "breadcrumb" "footer" "menu" "navbar" "nav" "search" "sidebar" "social"])

(def ^:private boilerplate-classes
  "Exact class tokens that mark chrome. Matched token-wise, not by substring:
   `bio` must not fire on `biography`."
  #{"bio" "photo" "toc" "share" "related" "comments" "subscribe" "newsletter"
    "cookie" "cookie-banner" "promo" "byline-photo"})

(def ^:private block-tags
  "Tags whose boundaries end a block of text. Extraction emits one paragraph per
   block, which is what every line-anchored chunk strategy reads."
  #{"p" "div" "section" "article" "main" "li" "ul" "ol" "dl" "dt" "dd"
    "blockquote" "table" "tr" "td" "th" "br" "hr" "figure" "figcaption"
    "h1" "h2" "h3" "h4" "h5" "h6" "pre"})

(defn- normalize-space
  [s]
  (-> (or s "")
      (str/replace #" " " ")
      (str/replace #"[ \t\r\f]+" " ")
      (str/replace #"\n[ \t]+" "\n")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defprotocol IHtmlStructureParser
  (parse-structure [this html]))

(defn- attrs-map
  [^MutableAttributeSet attrs]
  (into {}
        (map (fn [k]
               [(str/lower-case (str k))
                (str (.getAttribute attrs k))])
             (enumeration-seq (.getAttributeNames attrs)))))

(defn- class-tokens
  "The class attribute as a set of lowercase tokens."
  [attrs]
  (-> (str (get attrs "class")) str/lower-case (str/split #"\s+") set))

(defn- boilerplate-node?
  [tag attrs]
  (let [haystack (str/lower-case
                  (str tag " " (get attrs "id") " " (get attrs "class") " " (get attrs "role")))]
    (boolean
     (or (boilerplate-tags tag)
         (some boilerplate-classes (class-tokens attrs))
         (some #(str/includes? haystack %) boilerplate-fragments)))))

(defn- tag-name
  [tag]
  (str/lower-case (str tag)))

(defn- heading-level
  [tag]
  (case tag
    "h1" 1
    "h2" 2
    "h3" 3
    "h4" 4
    "h5" 5
    "h6" 6
    nil))

(defn- finish-parts
  [parts]
  (some-> (str/join " " parts) normalize-space not-empty))

(defn- block-prefix-for
  "Markdown prefix a block tag opens with. A heading carries its level as `#`s
   so the outline survives into the text itself, not only into metadata."
  [tag]
  (if-let [level (heading-level tag)]
    (str (apply str (repeat level "#")) " ")
    (case tag
      "li" "- "
      "blockquote" "> "
      "")))

(defrecord ParserDelegatorHtmlStructureParser []
  IHtmlStructureParser
  (parse-structure [_ html]
    (let [title-parts     (atom [])
          headings*       (atom [])
          links*          (atom [])
          authors*        (atom [])
          metadata        (atom {})
          canonical-url*  (atom nil)
          blocks*         (atom [])
          block-parts     (atom [])
          block-prefix    (atom "")
          pre-depth       (atom 0)
          pre-parts       (atom [])
          skip-depth      (atom 0)
          skip-tags       (atom [])
          current-heading (atom nil)
          current-link    (atom nil)
          in-title?       (atom false)
          flush-block!    (fn []
                            (when-let [text (finish-parts @block-parts)]
                              (swap! blocks* conj (str @block-prefix text)))
                            (reset! block-parts [])
                            (reset! block-prefix ""))
          flush-pre!      (fn []
                            (let [raw (str/trim (str/join @pre-parts))]
                              (when (seq raw)
                                (swap! blocks* conj (str "```\n" raw "\n```"))))
                            (reset! pre-parts []))
          add-metadata!   (fn [attrs]
                            (let [content (some-> (get attrs "content") normalize-space not-empty)
                                  k       (some-> (or (get attrs "name") (get attrs "property"))
                                                  str/lower-case)]
                              (when content
                                (case k
                                  "description" (swap! metadata assoc :description content)
                                  "og:description" (swap! metadata assoc :og-description content)
                                  "og:type" (swap! metadata assoc :og-type (str/lower-case content))
                                  "article:published_time" (swap! metadata assoc :published content)
                                  nil))))
          add-canonical!  (fn [attrs]
                            (when (= "canonical" (some-> (get attrs "rel") str/lower-case))
                              (when-let [href (some-> (get attrs "href") normalize-space not-empty)]
                                (reset! canonical-url* href))))
          start!          (fn [tag attrs]
                            (cond
                              (boilerplate-node? tag attrs)
                              (do
                                (swap! skip-tags conj tag)
                                (swap! skip-depth inc))

                              (= "title" tag)
                              (reset! in-title? true)

                              (= "meta" tag)
                              (add-metadata! attrs)

                              (= "link" tag)
                              (add-canonical! attrs)

                              (= "pre" tag)
                              (do (flush-block!)
                                  (swap! pre-depth inc))

                              (= "a" tag)
                              (reset! current-link
                                      {:href        (some-> (get attrs "href") normalize-space not-empty)
                                       :author?     (= "author" (some-> (get attrs "rel") str/lower-case))
                                       :in-content? (zero? @skip-depth)
                                       :text        []})

                              :else
                              (do
                                (when (block-tags tag)
                                  (flush-block!)
                                  (reset! block-prefix (block-prefix-for tag)))
                                (when-let [level (heading-level tag)]
                                  (reset! current-heading {:level level :tag tag :text []})))))
          simple!         (fn [tag attrs]
                            (cond
                              (boilerplate-tags tag)
                              (start! tag attrs)

                              (boilerplate-node? tag attrs)
                              nil

                              :else
                              (start! tag attrs)))
          end!            (fn [tag]
                            (cond
                              (and (seq @skip-tags) (= tag (peek @skip-tags)))
                              (do
                                (swap! skip-tags pop)
                                (swap! skip-depth dec))

                              (= "title" tag)
                              (reset! in-title? false)

                              (= "pre" tag)
                              (do (flush-pre!)
                                  (swap! pre-depth #(max 0 (dec %))))

                              :else
                              (do
                                (when (and @current-heading (= tag (:tag @current-heading)))
                                  (when-let [text (finish-parts (:text @current-heading))]
                                    (swap! headings* conj {:level (:level @current-heading)
                                                           :text  text}))
                                  (reset! current-heading nil))
                                (when (= "a" tag)
                                  (let [{:keys [href author? in-content? text]} @current-link
                                        label (finish-parts text)]
                                    (when href
                                      (swap! links* conj {:href        href
                                                          :text        (or label href)
                                                          :in-content? (boolean in-content?)}))
                                    (when (and author? label)
                                      (swap! authors* conj label)))
                                  (reset! current-link nil))
                                (when (block-tags tag)
                                  (flush-block!)))))
          text!           (fn [text]
                            (if (pos? @pre-depth)
                              (when (zero? @skip-depth)
                                (swap! pre-parts conj text))
                              (let [text (normalize-space text)]
                                (when (seq text)
                                  (when @in-title?
                                    (swap! title-parts conj text))
                                  (when (zero? @skip-depth)
                                    (swap! block-parts conj text)
                                    (when @current-heading
                                      (swap! current-heading update :text conj text))
                                    (when @current-link
                                      (swap! current-link update :text conj text)))))))]
      (.parse (ParserDelegator.)
              (StringReader. (or html ""))
              (proxy [HTMLEditorKit$ParserCallback] []
                (handleStartTag [tag attrs _pos]
                  (start! (tag-name tag) (attrs-map attrs)))
                (handleSimpleTag [tag attrs _pos]
                  (let [tag*  (tag-name tag)
                        attrs* (attrs-map attrs)]
                    (if (contains? attrs* "endtag")
                      (end! tag*)
                      (simple! tag* attrs*))))
                (handleEndTag [tag _pos]
                  (end! (tag-name tag)))
                (handleText [data _pos]
                  (text! (String. data))))
              true)
      (flush-pre!)
      (flush-block!)
      {:title         (finish-parts @title-parts)
       :description   (or (:description @metadata) (:og-description @metadata))
       :canonical-url @canonical-url*
       :og-type       (:og-type @metadata)
       :published     (:published @metadata)
       :authors       (vec (distinct @authors*))
       :headings      (filterv (comp seq :text) @headings*)
       :links         (filterv (comp seq :href) @links*)
       :content       (str/join "\n\n" @blocks*)})))

(def default-structure-parser
  (->ParserDelegatorHtmlStructureParser))

(defrecord ParserDelegatorHtmlTextExtractor [structure-parser]
  IHtmlTextExtractor
  (extract-text [_ html]
    (or (:content (parse-structure structure-parser html)) "")))

(def default-text-extractor
  (->ParserDelegatorHtmlTextExtractor default-structure-parser))

(defprotocol IWebsiteMetadataParser
  (parse-metadata [this structure url]))

(defrecord StructureWebsiteMetadataParser []
  IWebsiteMetadataParser
  (parse-metadata [_ structure url]
    {:url           url
     :canonical-url (:canonical-url structure)
     :title         (or (:title structure) url)
     :description   (:description structure)}))

(def default-metadata-parser
  (->StructureWebsiteMetadataParser))

(defprotocol IWebsiteParser
  (parse-website-page [this html opts]
    "BODY as a page map {:url :canonical-url :title :description :content
     :headings :links ...}. opts carries :url. Returns Result<page>."))

(defrecord DefaultWebsiteParser [text-extractor structure-parser metadata-parser]
  IWebsiteParser
  (parse-website-page [_ html {:keys [url]}]
    (cond
      (str/blank? html)
      (r/err :source/empty-html {:url url})

      :else
      (r/try-effect* :source/parse-failed
                     (let [structure (parse-structure structure-parser html)
                           metadata  (parse-metadata metadata-parser structure url)
                           content   (or (not-empty (:content structure))
                                         (some-> (extract-text text-extractor html) normalize-space)
                                         "")]
                       (assoc metadata
                              :content content
                              :headings (:headings structure)
                              :links (:links structure)
                              :authors (:authors structure)
                              :og-type (:og-type structure)
                              :published (:published structure)))))))

(defn default-website-parser
  []
  (->DefaultWebsiteParser default-text-extractor
                          default-structure-parser
                          default-metadata-parser))

(defn parse-page
  "Parse one HTML page into page fields with the default parser.
   opts carries :url. Returns Result<page>."
  [html opts]
  (parse-website-page (default-website-parser) html opts))
