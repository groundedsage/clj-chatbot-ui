# clj chatbot ui

This is a clone of [chatbot-ui](https://github.com/mckaywrigley/chatbot-ui) built in [electric](https://github.com/hyperfiddle/electric) and [datahike](https://github.com/replikativ/datahike).

I have made some minor modifications to the UI and UX and will do a full redesign once all the base issues are resolved.

## Run the application

`npm install` to install Tailwind and other javasscript dependencies

`npm run build:tailwind:dev` to build the css watch and build

Entities are collected from `config.edn`  

```clojure
{:all-entities-image ""
 :entities [{:name ""
             :full-name ""
             :image ""
             :prompt ""}
            {:name ""
             :image ""
             :prompt "}]}
```

Dev build:

* Shell: `clj -A:dev -X dev/-main`, or repl: `(dev/-main)`
* http://localhost:8080
* Electric root function: [src/electric_starter_app/main.cljc](src/electric_starter_app/main.cljc)
* Hot code reloading works: edit -> save -> see app reload in browser

Prod build:

```shell
clj -X:build:prod build-client
clj -M:prod -m prod
```
