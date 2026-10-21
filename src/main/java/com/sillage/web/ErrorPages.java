package com.sillage.web;

import com.sillage.core.Util;

/**
 * Server-rendered error pages. They inherit the same editorial typography as the rest of the
 * front end so a wrong turn never drops the visitor into a bare browser message.
 */
public final class ErrorPages {

    private ErrorPages() {}

    /**
     * Token replacement rather than {@code String.format}: the embedded stylesheet is full of
     * literal percent signs ({@code width:100%}) which a format string would choke on.
     */
    private static String shell(String kicker, String title, String body, String actionLabel, String actionHref) {
        String page = """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>{{title}} \u00b7 Sillage</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Bodoni+Moda:ital,opsz,wght@0,6..96,400..900;1,6..96,400..900&family=Jost:wght@300;400;500;600&family=IBM+Plex+Mono:wght@400;500&display=swap" rel="stylesheet">
                <style>
                  :root{--paper:#f4f1ea;--ink:#151310;--brass:#9c7b3f;--stone:#cfc8ba}
                  *{box-sizing:border-box}
                  body{margin:0;background:var(--paper);color:var(--ink);
                    font-family:'Jost','Avenir Next','Segoe UI',system-ui,sans-serif;
                    min-height:100vh;display:grid;place-items:center;padding:6vh 6vw}
                  .frame{max-width:44rem;width:100%;border-top:2px solid var(--ink);padding-top:1.2rem}
                  .kicker{font-family:'IBM Plex Mono',ui-monospace,monospace;font-size:.7rem;letter-spacing:.34em;
                    text-transform:uppercase;color:var(--brass)}
                  h1{font-family:'Bodoni Moda',Didot,'Times New Roman',serif;font-weight:500;
                    font-size:clamp(3rem,11vw,7.5rem);line-height:.92;margin:.35rem 0 1rem;letter-spacing:-.02em}
                  h1 em{font-style:italic;color:var(--brass)}
                  p{font-size:1.02rem;line-height:1.65;max-width:34rem;color:#4a443b;margin:0 0 2rem}
                  .actions{display:flex;gap:1.6rem;flex-wrap:wrap;align-items:center}
                  a{color:var(--ink);text-decoration:none;border-bottom:1px solid var(--stone);
                    padding-bottom:.2rem;font-size:.82rem;letter-spacing:.16em;text-transform:uppercase;
                    font-family:'IBM Plex Mono',ui-monospace,monospace}
                  a.primary{background:var(--ink);color:var(--paper);border-color:var(--ink);padding:.85rem 1.6rem}
                  a:focus-visible{outline:2px solid var(--brass);outline-offset:3px}
                  .rule{height:1px;background:var(--stone);margin:3rem 0 1rem}
                  .meta{font-family:'IBM Plex Mono',ui-monospace,monospace;font-size:.68rem;letter-spacing:.18em;
                    text-transform:uppercase;color:#8b8478}
                  @media (prefers-reduced-motion:no-preference){h1{animation:rise .7s cubic-bezier(.2,.7,.2,1) both}
                    @keyframes rise{from{opacity:0;transform:translateY(14px)}to{opacity:1;transform:none}}}
                </style>
                </head>
                <body>
                  <main class="frame">
                    <p class="kicker">{{kicker}}</p>
                    <h1>{{title}}</h1>
                    <p>{{body}}</p>
                    <div class="actions">
                      <a class="primary" href="{{href}}">{{label}}</a>
                      <a href="/">Front page</a>
                      <a href="/studio">Studio console</a>
                    </div>
                    <div class="rule"></div>
                    <p class="meta">Sillage \u00b7 maison de coiffure &amp; beaut\u00e9 \u00b7 est. 1987</p>
                  </main>
                </body>
                </html>
                """;
        return page.replace("{{title}}", Util.nz(title))
                .replace("{{kicker}}", Util.nz(kicker))
                .replace("{{body}}", Util.nz(body))
                .replace("{{href}}", Util.nz(actionHref, "/"))
                .replace("{{label}}", Util.nz(actionLabel, "Continue"));
    }

    public static String notFound(String path) {
        return shell("Error 404 \u2014 page not on the index",
                "Not in <em>this</em> issue",
                "We looked through the whole run and there is nothing filed under <strong>"
                        + Util.escapeHtml(Util.truncate(path, 80))
                        + "</strong>. It may have been renamed, moved to another edition, or never printed at all.",
                "Back to safety", "/");
    }

    public static String serverError(String requestId) {
        return shell("Error 500 \u2014 the presses jammed",
                "A small <em>malfunction</em>",
                "Something failed behind the scenes. Nothing has been lost \u2014 the booking book is written to disk "
                        + "atomically. If you quote reference <strong>" + Util.escapeHtml(Util.nz(requestId, "n/a"))
                        + "</strong> at the desk we can find the exact line in the log.",
                "Try the console", "/studio");
    }

    public static String maintenance() {
        return shell("Closed \u2014 briefly",
                "Be right <em>back</em>",
                "The studio is being reset. Give it a moment and reload.",
                "Reload the front page", "/");
    }
}
