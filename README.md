# hive-html

An HTML page as text, headings, links and metadata. JDK only: the parser
behind it is `javax.swing.text.html.parser.ParserDelegator`, so the library
adds nothing to a classpath but itself and `hive-dsl` for the `Result` it
returns.

It knows nothing about ingestion, crawling or search. It answers one question:
given the markup of a page, what did the page say?

```clojure
(require '[hive-html.page :as page])

(page/parse-page html {:url "https://docs.example.com/api"})
;; => {:ok {:url "https://docs.example.com/api"
;;          :canonical-url "https://docs.example.com/api"
;;          :title "API Guide"
;;          :description "Reference docs"
;;          :content "# API\n\nUse tokens."
;;          :headings [{:level 1 :text "API"}]
;;          :links [{:href "/auth" :text "Auth guide" :in-content? true}]
;;          :authors [] :og-type nil :published nil}}
```

## What the content looks like

One block per paragraph, separated by a blank line. Headings are markdown
heading lines, so the outline survives into the text. List items keep a
bullet, blockquotes a `>`, and `<pre>` blocks are fenced with their newlines
intact.

Chrome is dropped: `nav`, `header`, `footer`, `aside`, `form`, scripts and
styles, and any node whose tag, id, class or role names a sidebar, a menu, a
breadcrumb, a cookie banner or a share widget. Links found inside chrome are
still collected, marked `:in-content? false`, so a crawler can decide for
itself whether to follow the site map or only what the article points at.

## Ports

| Protocol                 | Method               | Default                             |
|--------------------------|----------------------|-------------------------------------|
| `IHtmlStructureParser`   | `parse-structure`    | `default-structure-parser`          |
| `IHtmlTextExtractor`     | `extract-text`       | `default-text-extractor`            |
| `IWebsiteMetadataParser` | `parse-metadata`     | `default-metadata-parser`           |
| `IWebsiteParser`         | `parse-website-page` | `(default-website-parser)`          |

`IWebsiteParser` is the one a consumer injects at: anything that turns a body
plus `{:url ...}` into a page map and returns a `Result` can stand in for the
default, and a plaintext parser is one such thing.

## Fixture

`hive_html/fixtures/article.html` ships on the classpath. It carries every
structural feature the parser reads, so a consumer's suite can parse the same
page this suite does.

## License

MIT.
