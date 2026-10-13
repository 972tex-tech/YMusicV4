// Scraping logic home — the remotely-updatable extraction code (feed/download resolvers) registers
// its onAccept(command, commandHandler(...)) handlers from here.
// WHATWG fetch/xhr (eval'd base.js + SABR) live in the player bundle (player-index → boot).
import "./diagnostics";
import "./yt/commands";
import "./yt/musicCommands";
import "./yt/login";
import "./yt/videoDetails";
import "./yt/playlistEdit";
import "./yt/links";
