import vm from 'node:vm';
import assert from 'node:assert/strict';
export function verify(original,fixed) {
 function harness(config) {
  let now=100000;
  const c=vm.createContext({dc_mode:'now',dc_c1_session:0,dc_c2_session:0,dc_budget_kw:0,
   Date:{now:()=>now},console:{info:()=>{}}});
  const run=s=>vm.runInContext(s,c);
  const heartbeat=config.enable.out[0].config.set.set.script;
  return {c,tick(time=now){now=time;c.dc_budget_kw=run(heartbeat);return c.dc_budget_kw;},
   enabled(){return run(config.enabled.script);},current(value){c.maxcurrent=value;run(config.maxcurrent.script);},
   enable(value){c.enable=value;run(config.enable.script);this.tick();}};
 }
 // Replay the lost-enable race against both actual YAML scripts.
 function race(config) {
  const h=harness(config);h.tick();const prearm=h.enabled();h.current(47);h.enable(true);
  h.tick(101000);h.c.dc_c2_session=1;
  const budgets=[h.tick(102000),h.tick(107000),h.tick(113000),h.tick(170000)];
  return {prearmReportedEnabled:prearm,budgets};
 }
 const before=race(original),after=race(fixed);
 assert.equal(before.prearmReportedEnabled,true);
 assert.deepEqual(before.budgets,[5,5,0,0]);
 assert.equal(after.prearmReportedEnabled,false);
 assert.deepEqual(after.budgets,[33,33,33,33]);
 // Physical connection before, during and after the evcc enable call.
 let cases=0;
 for(const mode of ['now','pv','smart']) for(const connector of [1,2])
 for(const early of [false,true]) for(const current of [0,1,6,7,10,47,60,1000]) {
  const h=harness(fixed);h.c.dc_mode=mode;h.tick();
  assert.equal(h.enabled(),false);
  if(early)h.c['dc_c'+connector+'_session']=1;
  h.current(current);h.enable(true);
  if(!early){h.tick(101000);h.c['dc_c'+connector+'_session']=1;}
  const expected=current>0?Math.max(5,Math.min(35,Math.ceil(current*Math.sqrt(3)*400/1000))):0;
  assert.equal(h.tick(102000),expected);
  assert.equal(h.tick(180000),expected);
  assert.equal(h.enabled(),expected>=5);
  // A real evcc stop and mode off must still withdraw the budget immediately.
  h.enable(false);assert.equal(h.tick(181000),0);assert.equal(h.enabled(),false);
  h.current(current);h.enable(true);h.c.dc_mode='off';assert.equal(h.tick(182000),0);
  assert.equal(h.enabled(),false);
  cases++;
 }
 // Without an evcc enable, the bounded startup grace still expires.
 const h=harness(fixed);h.tick();h.c.dc_c2_session=1;
 assert.equal(h.tick(102000),5);assert.equal(h.tick(113000),0);
 assert.equal(h.enabled(),false);
 // Two-session reconnect: an explicit enable survives the idle interval again.
 const r=harness(fixed);r.tick();r.current(7);r.enable(true);r.c.dc_c2_session=1;
 assert.equal(r.tick(102000),5);r.enable(false);r.c.dc_c2_session=0;r.tick(110000);
 r.current(47);r.enable(true);r.tick(115000);r.c.dc_c2_session=1;
 assert.equal(r.tick(130000),33);assert.equal(r.tick(230000),33);
 return {lostEnableRaceBefore:before,lostEnableRaceAfter:after,connectionAndModeCases:cases,
  explicitStop:'passed',modeOff:'passed',missingEnableGraceExpiry:'passed',reconnect:'passed'};
}

import fs from "node:fs/promises";
const read = name => fs.readFile(new URL(name, import.meta.url), "utf8");
const previous = JSON.parse(await read("regression-v2.json"));
const current = {enabled: {script: await read("enabled.js")}, enable: {script: await read("enable.js"), out: [{config: {set: {set: {script: await read("refresh.js")}}}}]}, maxcurrent: {script: await read("maxcurrent.js")}};
export const result = verify(previous, current);
console.log(JSON.stringify(result));
