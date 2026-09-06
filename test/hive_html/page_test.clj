(ns hive-html.page-test
  "Block structure, boilerplate and metadata of a parsed page."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-html.page :as page]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def article-html
  "A long-form article carrying every structural feature the parser reads.
   Shipped as a resource so a consumer's suite can parse the same page."
  (slurp (io/resource "hive_html/fixtures/article.html")))

(defn- structure [html]
  (page/parse-structure page/default-structure-parser html))

;; =============================================================================
;; Block structure
;; =============================================================================

(deftest headings-carry-their-level-into-the-text
  (let [content (:content (structure article-html))
        lines   (str/split-lines content)]
    (testing "a heading is emitted as a markdown heading line, not inline prose"
      (is (some #(= "# Legacy Displacement" %) lines))
      (is (some #(= "## Extract Value Streams" %) lines)))
    (testing "the level is the number of hashes"
      (is (= 1 (count (re-find #"^#+" (first (filter #(str/includes? % "Legacy Displacement")
                                                     lines)))))))))

(deftest paragraphs-are-separate-blocks
  (let [content (:content (structure article-html))]
    (testing "distinct paragraphs do not run together on one line"
      (is (str/includes? content "First paragraph of the thesis.\n"))
      (is (not (str/includes? content
                              "First paragraph of the thesis. Second paragraph"))))
    (testing "blocks are separated by a blank line, so line-anchored strategies see them"
      (is (str/includes? content "\n\n")))))

(deftest list-items-keep-their-bullet
  (let [lines (str/split-lines (:content (structure article-html)))]
    (is (some #(= "- One force" %) lines))
    (is (some #(= "- Another force" %) lines))))

(deftest preformatted-text-is-fenced-and-verbatim
  (let [content (:content (structure article-html))]
    (testing "a pre block is fenced"
      (is (str/includes? content "```")))
    (testing "its internal newlines survive, which is the whole point of a code sample"
      (is (str/includes? content "(defn displace [system]\n  (:legacy system))")))))

;; =============================================================================
;; Boilerplate
;; =============================================================================

(deftest author-bio-prose-is-chrome-but-the-author-is-metadata
  (let [{:keys [content authors]} (structure article-html)]
    (testing "the bio paragraph does not enter the corpus"
      (is (not (str/includes? content "has written many things"))))
    (testing "the author still reaches metadata via rel=author"
      (is (= ["Ian Cartwright"] authors)))))

(deftest class-tokens-are-matched-whole
  (testing "`biography-of-a-language` is not the `bio` class"
    (is (str/includes? (:content (structure article-html))
                       "class token is not"))))

(deftest navigation-is-still-dropped
  (is (not (str/includes? (:content (structure article-html)) "Home"))))

(deftest a-link-knows-whether-it-is-content-or-chrome
  (let [links (:links (structure article-html))
        by-href (into {} (map (juxt :href identity)) links)]
    (testing "a nav link is still collected: a crawler may want the site map"
      (is (contains? by-href "/index")))
    (testing "but it is marked as chrome, so a caller can follow only what the page says"
      (is (false? (:in-content? (get by-href "/index"))))
      (is (true? (:in-content? (get by-href "/ian")))))))

;; =============================================================================
;; The composed page
;; =============================================================================

(deftest parse-page-carries-metadata-and-content
  (let [html   (str "<html><head><title>API Guide</title>"
                    "<meta name=\"description\" content=\"Reference docs\">"
                    "<link rel=\"canonical\" href=\"https://docs.example.com/api\"></head>"
                    "<body><nav>Skip me</nav><main><h1>API</h1><p>Use tokens.</p>"
                    "<a href=\"/auth\">Auth guide</a></main></body></html>")
        result (page/parse-page html {:url "https://docs.example.com/api?x=1"})]
    (is (r/ok? result))
    (let [p (:ok result)]
      (is (= "API Guide" (:title p)))
      (is (= "Reference docs" (:description p)))
      (is (= "https://docs.example.com/api" (:canonical-url p)))
      (is (= [{:level 1 :text "API"}] (:headings p)))
      (is (= [{:href "/auth" :text "Auth guide" :in-content? true}] (:links p)))
      (is (str/includes? (:content p) "Use tokens."))
      (is (not (str/includes? (:content p) "Skip me"))))))

(deftest a-blank-page-is-an-error-not-an-exception
  (is (r/err? (page/parse-page "" {:url "https://x/y"})))
  (is (r/err? (page/parse-page nil {:url "https://x/y"}))))

(deftest og-metadata-reaches-the-page
  (let [p (:ok (page/parse-page article-html {:url "https://example.com/a"}))]
    (is (= "article" (:og-type p)))
    (is (= "Replacing systems incrementally" (:description p)))
    (is (= ["Ian Cartwright"] (:authors p)))))
