# clj chatbot ui

This is a clone of [chatbot-ui](https://github.com/mckaywrigley/chatbot-ui) built in [electric](https://github.com/hyperfiddle/electric) and [datahike](https://github.com/replikativ/datahike).

I have made some minor modifications to the UI and UX and will do a full redesign once all the base issues are resolved.

## Preparations required to develop

This currently depends on a local build of Datahike.

You will need to clone the [repository](https://github.com/replikativ/datahike)

`git checkout 638-improve-connection-handling` to switch to the branch used for this build.

Run `bb jar` to build it. 

Update the jar filename and location in `deps.edn`
`io.replikativ/datahike {:local/root "../../scratch/datahike/target/datahike-0.6.1587.jar"}`

## Run the application

`yarn` to install Tailwind and other javasscript dependencies

`yarn build:tailwind:dev` to build the css watch and build

In a seperate terminal window run this command to start the application

```
$ clj -A:dev -X user/main

Starting Electric compiler and server...
shadow-cljs - server version: 2.20.1 running at http://localhost:9630
shadow-cljs - nREPL server started on port 9001
[:app] Configuring build.
[:app] Compiling ...
[:app] Build completed. (224 files, 0 compiled, 0 warnings, 1.93s)

👉 App server available at http://0.0.0.0:8080
```