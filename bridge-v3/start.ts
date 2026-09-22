import { serve } from "@hono/node-server";
import app from "./server";
import { publishInstagramStory } from "./stories";

const port=Number(process.env.PORT||8080);
serve({fetch:app.fetch,port});
console.log("fuoconero-social-bridge-v3 listening on "+port);

async function runApprovedStoryOnce(){
  if(String(process.env.STORY_RUN_ON_START||"")!=="1") return;
  const videoUrl=String(process.env.STORY_RUN_VIDEO_URL||"");
  const runId=String(process.env.STORY_RUN_ID||"");
  if(!videoUrl.startsWith("https://")){
    console.error("STORY_RUN_FAILED "+JSON.stringify({run_id:runId,error:"video_url non valido"}));
    return;
  }
  const result=await publishInstagramStory(videoUrl);
  console.log("STORY_RUN_RESULT "+JSON.stringify({run_id:runId,video_url:videoUrl,...result}));
}
setTimeout(()=>{runApprovedStoryOnce().catch(e=>console.error("STORY_RUN_FAILED "+String(e)))},5000);
