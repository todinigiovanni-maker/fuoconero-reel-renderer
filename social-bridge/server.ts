import { Hono } from "hono";
import { publishInstagramStory, publishFacebookStory } from "./stories";
const app=new Hono();
const auth=(c:any)=>Boolean(process.env.BRIDGE_SECRET && c.req.header("Authorization")==="Bearer "+process.env.BRIDGE_SECRET);
app.get("/health",c=>c.json({ok:true,service:"fuoconero-social-bridge-staging",stories:true}));
app.post("/instagram/story",async c=>{if(!auth(c))return c.json({ok:false,error:"Non autorizzato"},401);const b:any=await c.req.json();if(b.confirmed!==true)return c.json({ok:false,error:"Pubblicazione non confermata"},400);const u=String(b.video_url||"");if(!u.startsWith("https://"))return c.json({ok:false,error:"video_url non valido"},400);const x=await publishInstagramStory(u);return c.json(x,x.success?200:502);});
app.post("/facebook/story",async c=>{if(!auth(c))return c.json({ok:false,error:"Non autorizzato"},401);const b:any=await c.req.json();if(b.confirmed!==true)return c.json({ok:false,error:"Pubblicazione non confermata"},400);const x=await publishFacebookStory(String(b.video_url||""));return c.json(x,x.success?200:501);});
export default app;
