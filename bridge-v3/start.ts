import { serve } from "@hono/node-server";
import app from "./server";
const port=Number(process.env.PORT||8080);
serve({fetch:app.fetch,port});
console.log("fuoconero-social-bridge-v3 listening on "+port);
