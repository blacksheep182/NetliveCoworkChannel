'use strict';
const {test,before,after}=require('node:test');
const assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
let browser,server,base;
const source=process.env.CHANNEL_STATIC||path.resolve(__dirname,'../../main/resources/static');
before(async()=>{
  const assets={'/web.html':'text/html','/web.js':'application/javascript','/mobile.html':'text/html','/mobile.css':'text/css'};
  server=http.createServer((req,res)=>{
    const type=assets[req.url];
    if(!type){res.writeHead(404).end();return;}
    res.setHeader('Content-Type',`${type}; charset=utf-8`);
    res.end(fs.readFileSync(path.join(source,req.url.slice(1))));
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));base=`http://127.0.0.1:${server.address().port}`;
  browser=await chromium.launch({channel:'msedge',headless:true});
});
after(async()=>{await browser?.close();await new Promise(resolve=>server.close(resolve));});
const workspace={sessionWorkspaceRef:'sws-test',label:'测试工作空间'};
const first={workspace,targetRef:'target-1',label:'真实会话 Alpha',available:true};
const second={workspace,targetRef:'target-2',label:'真实会话 Beta',available:true};
const snapshot=(ref='target-1',status='completed')=>({...([first,second].find(s=>s.targetRef===ref)||first),execution:{status,revision:17,observedAt:new Date().toISOString()}});
async function setup(options={}){
  const context=await browser.newContext({viewport:options.viewport||{width:1200,height:850}}),page=await context.newPage(),sent=[],polls=[],lookups=[],acks=[],selections=new Map(),navigationRequests=new Map();
  await page.route('**/web/**',async route=>{
    const req=route.request(),url=new URL(req.url());assert.equal(req.headers().authorization,'Bearer test-token');assert.equal(url.searchParams.has('token'),false);
    const reply=body=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});
    if(req.method()==='GET'){
      if(url.pathname.includes('/requests/')){
        lookups.push(url.pathname);
        const saved=navigationRequests.get(url.pathname.split('/').at(-1));
        if(saved?.result.type==='session.create'&&options.creation?.lookup)return reply(options.creation.lookup(saved));
        return reply(saved||((typeof options.lookup==='function'?options.lookup():options.lookup)||{status:'accepted',taskRef:'task-1'}));
      }
      polls.push(url);return reply(options.poll?options.poll(url):{messages:[],next:0,streamId:'stream-1'});
    }
    const body=req.postDataJSON(),envelope=body;
    if(url.pathname.endsWith('/inbox/ack')){acks.push(body);return reply({status:'completed'});}
    const routes={'selection/query':'selection.get','workspaces/query':'workspace.list','workspaces/select':'workspace.select','sessions/select':'session.select',
      'sessions/query':'session.list','sessions/get':'session.get','sessions/create':'session.create','chat/messages':'input.submit'};
    const routeName=url.pathname.split('/').slice(-2).join('/'),type=routes[routeName];
    sent.push({path:url.pathname,...body});
    if(type){assert.equal(body.operation,undefined,'browser must not construct the common Channel operation');assert.match(body.viewId,/^[a-zA-Z0-9_-]{16,80}$/);}
    let selection=selections.get(envelope.viewId)||{scopeRef:'scope-'+envelope.viewId,sessionWorkspaceRef:null,targetRef:null,revision:0};
    if(type==='selection.get')return reply({status:'completed',result:{type,selection:options.currentSelection?options.currentSelection(selection):selection}});
    const available=url.pathname.includes('other-entry')||options.empty?[]:options.pages?
      (options.pages[body.cursor||'first']?.items||[]):options.sessions||[first,second];
    if(type==='workspace.list'){
      if(selection.targetRef&&!available.some(s=>s.targetRef===selection.targetRef)&&!options.pages?.['20']?.items.some(s=>s.targetRef===selection.targetRef)){
        selection={...selection,targetRef:null,revision:selection.revision+1};selections.set(envelope.viewId,selection);
      }
      return reply({status:'completed',result:{type,items:available.length?(options.workspaces||[workspace]):[],selection}});
    }
    if(type==='workspace.select'||type==='session.select'){
      assert.equal(body.expectedBindingRevision,selection.revision);
      selection={...selection,sessionWorkspaceRef:type==='workspace.select'?body.sessionWorkspaceRef:selection.sessionWorkspaceRef,
        targetRef:type==='workspace.select'?null:body.targetRef,revision:selection.revision+1};selections.set(envelope.viewId,selection);
      const result={status:'completed',requestId:body.requestId,result:type==='workspace.select'?{type,selection}:{type,...selection}};
      navigationRequests.set(body.requestId,result);
      if(options.navigation)return options.navigation(route,body,reply,result,type);
      return reply(result);
    }
    if(type==='session.create'){
      const created=options.creation.session;
      assert.equal(body.expectedBindingRevision,selection.revision);
      assert.equal(body.sessionWorkspaceRef,created.workspace.sessionWorkspaceRef);
      options.sessions.push(created);
      selection={...selection,sessionWorkspaceRef:body.sessionWorkspaceRef,targetRef:created.targetRef,revision:selection.revision+1};
      selections.set(envelope.viewId,selection);
      const result={status:'completed',requestId:body.requestId,result:{type,session:created,selection}};
      navigationRequests.set(body.requestId,result);
      return reply(options.creation.unknown?{status:'unknown'}:result);
    }
    if(type==='session.list')return reply({status:'completed',result:{type,items:available,
      ...(options.pages?.[body.cursor||'first']?.nextCursor?{nextCursor:options.pages[body.cursor||'first'].nextCursor}:{})}});
    if(type==='session.get')return reply(options.statusReply?options.statusReply(body):options.statusAccepted?{status:'accepted'}:
      {status:'completed',result:{type,session:options.snapshot?options.snapshot(body.targetRef):snapshot(body.targetRef)}});
    if(url.pathname.endsWith('/sessions/history'))return options.history?options.history(route,body,reply):reply({targetRef:body.targetRef,items:[]});
    if(url.pathname.endsWith('/interactions/respond'))return options.respond?options.respond(route,body,reply):reply({status:'completed',result:{type:'interaction.respond',interactionRef:body.interactionRef}});
    assert.equal(type,'input.submit','normal UI must submit the shared typed operation');
    assert.equal(body.expectedBindingRevision,selection.revision);assert.equal(body.targetRef,selection.targetRef);
    assert.equal(body.content,undefined,'L1 converts text to Channel content parts');
    if(options.submit)return options.submit(route,body,reply);
    return reply({status:'accepted',taskRef:'task-1',requestId:body.requestId});
  });
  await page.goto(base+(options.mobile?'/mobile.html':'/web.html'));
  if(options.mobile){
    assert.equal(await page.locator('#connection-dialog').isVisible(),false);
    await page.locator('#open-connection').click();
  }
  await page.locator('#entry').fill('web-test');await page.locator('#token').fill('test-token');await page.locator('#connect').click();
  await page.waitForFunction(()=>document.querySelector('#session-count').textContent.startsWith('已授权'));
  if(!options.empty&&options.chooseWorkspace!==false){await page.locator('#workspace').selectOption('sws-test');await page.waitForFunction(()=>!document.querySelector('#workspace').disabled);}
  async function choose(ref='target-1'){
    await page.getByRole('button',{name:ref==='target-1'?first.label:second.label,exact:true}).click();
    await page.waitForFunction(()=>!document.querySelector('#check').disabled);
  }
  return{page,context,sent,polls,lookups,acks,choose};
}

test('mobile panes preserve drafts and acknowledge messages only after the conversation is visible',async()=>{
  let deliver=false;
  const f=await setup({mobile:true,viewport:{width:390,height:844},poll:()=>({consumerRef:'phone-consumer',
    streamId:'mobile-inbox',messages:deliver?[{sequence:1,deliveryId:'phone-message',targetRef:first.targetRef,
      type:'message.ready',text:'这条消息在会话列表中到达。'}]:[]})});
  const {page}=f;
  try{
    await page.getByRole('searchbox',{name:'搜索会话'}).fill('Alpha');
    assert.equal(await page.getByRole('button',{name:second.label,exact:true}).count(),0);
    await f.choose();
    assert.equal(await page.getByRole('navigation',{name:'已授权会话'}).isVisible(),false);
    assert.equal(await page.locator('#current').evaluate(element=>element===document.activeElement),true);
    await page.locator('#text').fill('手机草稿');
    await page.locator('#text').press('Enter');
    assert.equal(await page.locator('#text').inputValue(),'手机草稿\n');
    await page.getByRole('button',{name:'返回会话列表'}).click();
    assert.equal(await page.locator('#text').isVisible(),false);
    deliver=true;
    await page.getByText('1 条新消息',{exact:true}).waitFor();
    assert.equal(f.acks.length,0);
    const readReceipt=page.waitForResponse(response=>response.url().endsWith('/inbox/ack'));
    await f.choose();
    assert.equal(await page.locator('#text').inputValue(),'手机草稿\n');
    await page.getByText('这条消息在会话列表中到达。',{exact:true}).waitFor();
    await readReceipt;
    assert.deepEqual(f.acks[0].deliveryIds,['phone-message']);
    assert.equal(f.sent.filter(request=>request.path.endsWith('/sessions/select')).length,1);
    await page.getByRole('button',{name:'发送消息'}).click();
    await page.getByText('消息已受理，正在等待回复。',{exact:true}).waitFor();
    assert.equal(f.sent.find(request=>request.path.endsWith('/chat/messages')).text,'手机草稿\n');
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
    await page.setViewportSize({width:1024,height:768});
    assert.equal(await page.locator('.mobile-shell').evaluate(element=>element.offsetWidth),460);
    assert.equal(await page.getByRole('navigation',{name:'已授权会话'}).isVisible(),false);
    await page.getByRole('button',{name:'返回会话列表'}).click();
    assert.equal(await page.locator('#text').isVisible(),false);
    const create=await page.getByRole('button',{name:'新建会话',exact:true}).boundingBox();
    assert.ok(create.y+create.height>740,'the action remains anchored at the bottom in a wide viewport');
  }finally{await f.context.close();}
});

test('mobile creation uses only enabled workspaces and queries the original request when the result is unknown',async()=>{
  let resolved=false;
  const allowed={...workspace,canCreate:true};
  const readOnly={sessionWorkspaceRef:'sws-read-only',label:'只读工作空间',canCreate:false};
  const added={workspace:allowed,targetRef:'created-session',label:'刚创建的手机会话',available:true};
  const f=await setup({mobile:true,chooseWorkspace:false,viewport:{width:320,height:640},
    workspaces:[allowed,readOnly],sessions:[first,second],
    creation:{session:added,unknown:true,lookup:result=>resolved?result:{status:'unknown'}},
    snapshot:ref=>ref===added.targetRef?{...snapshot(),...added}:snapshot(ref)});
  const {page}=f;
  try{
    await page.getByRole('button',{name:'新建会话',exact:true}).click();
    assert.deepEqual(await page.locator('#create-workspace option').evaluateAll(nodes=>nodes.filter(n=>n.value).map(n=>n.value)),[allowed.sessionWorkspaceRef]);
    await page.getByRole('button',{name:'创建并发送'}).click();
    await page.getByText('请输入第一条消息。',{exact:true}).waitFor();
    await page.getByRole('textbox',{name:'第一条消息'}).fill('手机新建的第一条消息');
    await page.getByRole('button',{name:'创建并发送'}).click();
    await page.locator('#create-check').waitFor();
    assert.equal(await page.getByRole('textbox',{name:'第一条消息'}).inputValue(),'手机新建的第一条消息');
    const submitted=f.sent.find(request=>request.path.endsWith('/sessions/create'));
    assert.equal(submitted.userPrompt,'手机新建的第一条消息');
    resolved=true;
    await page.waitForFunction(()=>document.querySelector('#create-check').hidden||!document.querySelector('#create-check').disabled);
    if(await page.locator('#create-dialog').isVisible())await page.locator('#create-check').click();
    await page.getByRole('heading',{name:added.label}).waitFor();
    assert.equal(await page.locator('#create-dialog').isVisible(),false);
    assert.equal(f.sent.filter(request=>request.path.endsWith('/sessions/create')).length,1);
    assert.ok(f.lookups.some(path=>path.endsWith('/requests/'+submitted.requestId)));
    assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  }finally{await f.context.close();}
});

test('mobile creation explains when the entry has no workspace with creation permission',async()=>{
  const f=await setup({mobile:true,chooseWorkspace:false,viewport:{width:390,height:844}});
  try{
    await f.page.getByRole('button',{name:'新建会话',exact:true}).click();
    await f.page.getByText('当前入口没有可新建会话的工作空间。',{exact:false}).waitFor();
    assert.equal(await f.page.getByRole('button',{name:'创建并发送'}).isVisible(),false);
    assert.equal(f.sent.filter(request=>request.path.endsWith('/sessions/create')).length,0);
  }finally{await f.context.close();}
});

test('completion from another workspace opens its authorized source and preserves the current draft',async()=>{
  let ready=false;
  const other={sessionWorkspaceRef:'sws-other',label:'另一个工作空间'},beta={...second,workspace:other};
  const completion={sequence:'1',deliveryId:'done-original',targetRef:first.targetRef,type:'task.completed',text:'任务已完成。',
    presentation:{source:{workspaceLabel:workspace.label,sessionLabel:first.label,taskSummary:'生成分析报告'},notification:{kind:'task_completed'}},
    packet:{type:'task.completed',payload:{resultMessageRefs:['result-final']}}};
  const f=await setup({workspaces:[workspace,other],sessions:[first,beta],snapshot:ref=>ref===second.targetRef?{...snapshot(ref),workspace:other}:snapshot(ref),
    poll:()=>({status:'page',consumerRef:'consumer-real',nextCursor:'stable-cursor',messages:ready?[completion]:[]})});
  try {
    await f.page.locator('#workspace').selectOption('sws-other');await f.page.waitForFunction(()=>!document.querySelector('#workspace').disabled);
    await f.choose('target-2');await f.page.locator('#text').fill('Beta 未发送草稿');ready=true;
    await f.page.getByRole('button',{name:'查看原会话'}).waitFor();assert.equal(await f.page.locator('#current').innerText(),second.label);
    assert.equal(await f.page.locator('#text').inputValue(),'Beta 未发送草稿');
    if(process.env.CHANNEL_V229_SCREENSHOT)await f.page.screenshot({path:process.env.CHANNEL_V229_SCREENSHOT});
    await f.page.getByRole('button',{name:'查看原会话'}).click();
    await f.page.waitForFunction(()=>document.querySelector('#current').textContent==='真实会话 Alpha');
    assert.equal(await f.page.locator('#workspace').inputValue(),'sws-test');
    await f.page.getByText('任务已完成，结果同步中…',{exact:true}).waitFor();
    assert.equal(f.sent.filter(r=>r.path.endsWith('/chat/messages')).length,0);
    await f.page.locator('#workspace').selectOption('sws-other');await f.page.waitForFunction(()=>!document.querySelector('#workspace').disabled);await f.choose('target-2');
    assert.equal(await f.page.locator('#text').inputValue(),'Beta 未发送草稿');
  }finally{await f.context.close();}
});

test('completion before result reconciles once without duplicating the answer or showing a success for waiting',async()=>{
  let stage=0;
  const completion={sequence:'1',deliveryId:'completion-once',targetRef:first.targetRef,type:'task.completed',text:'任务已完成。',
    presentation:{notification:{kind:'task_completed'}},packet:{type:'task.completed',payload:{resultMessageRefs:['answer-ref']}}};
  const f=await setup({poll:()=>({status:'page',nextCursor:'cursor',consumerRef:'consumer',messages:stage===0?[]:[completion,...(stage===2?[{sequence:'2',deliveryId:'answer-once',messageRef:'answer-ref',targetRef:first.targetRef,type:'message.ready',text:'完整的唯一答案'}]:[])]})});
  try {
    await f.choose();stage=1;await f.page.getByText('任务已完成，结果同步中…',{exact:true}).waitFor();stage=2;
    await f.page.getByText('完整的唯一答案',{exact:true}).waitFor();await f.page.getByText('任务已完成。',{exact:true}).waitFor();
    await f.page.waitForTimeout(2200);assert.equal(await f.page.getByText('完整的唯一答案',{exact:true}).count(),1);
    assert.equal(await f.page.locator('#task-notifications article').count(),0);
  }finally{await f.context.close();}
});

test('source navigation is disabled while loading instead of silently discarding a click',async()=>{
  let release,loading=false,ready=false;
  const gate=new Promise(resolve=>release=resolve);
  const f=await setup({history:async(route,body,reply)=>{loading=true;await gate;return reply({targetRef:body.targetRef,items:[]});},
    poll:()=>({status:'page',nextCursor:'busy-cursor',messages:ready?[{sequence:'1',deliveryId:'busy-completion',targetRef:second.targetRef,type:'task.completed',text:'任务已完成。',presentation:{notification:{kind:'task_completed'}}}]:[]})});
  try {
    const choosing=f.choose();while(!loading)await f.page.waitForTimeout(20);ready=true;
    const open=f.page.getByRole('button',{name:'查看原会话'});await open.waitFor();assert.equal(await open.isDisabled(),true);
    release();await choosing;await f.page.waitForFunction(()=>!document.querySelector('[data-source-navigation]').disabled);
    await open.click();await f.page.waitForFunction(()=>document.querySelector('#current').textContent==='真实会话 Beta');
  }finally{release();await f.context.close();}
});

test('retention gap requires explicit resynchronization and keeps the connection usable on a narrow screen',async()=>{
  const f=await setup({viewport:{width:390,height:844},poll:url=>url.searchParams.get('cursor')==='resync-authorized'
    ?{status:'page',nextCursor:'resync-authorized',messages:[]}:{status:'gap',reason:'retention',resyncCursor:'resync-authorized'}});
  try {
    await f.page.getByRole('button',{name:'重新同步收件'}).waitFor();assert.equal(f.polls.length,1);
    await f.page.getByRole('button',{name:'重新同步收件'}).click();await f.page.waitForFunction(()=>document.querySelector('#inbox-gap').hidden);
    await f.choose();assert.equal(await f.page.locator('#text').isEnabled(),true);
    assert.ok(await f.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  }finally{await f.context.close();}
});

test('session switching keeps catalogs and pagination and only reads target state plus uncached history',async()=>{
  const later={...first,targetRef:'target-3',label:'后续页会话'};
  const f=await setup({pages:{first:{items:[first,second],nextCursor:'20'},'20':{items:[later]}}});try{
    await f.page.locator('#more-sessions').click();await f.page.waitForFunction(()=>document.querySelectorAll('#session-list button').length===3);
    const baseline=f.sent.length;
    await f.choose();await f.page.waitForFunction(()=>!document.querySelector('#workspace').disabled);
    await f.choose('target-2');await f.page.waitForFunction(()=>!document.querySelector('#workspace').disabled);
    await f.choose();await f.page.waitForFunction(()=>!document.querySelector('#workspace').disabled);
    const requests=f.sent.slice(baseline);
    assert.equal(requests.filter(r=>r.path.endsWith('/workspaces/query')||r.path.endsWith('/sessions/query')||r.path.endsWith('/selection/query')).length,0);
    assert.deepEqual(requests.filter(r=>r.path.endsWith('/sessions/get')).map(r=>r.targetRef),['target-1','target-2','target-1']);
    assert.deepEqual(requests.filter(r=>r.path.endsWith('/sessions/history')).map(r=>r.targetRef),['target-1','target-2']);
    assert.equal(await f.page.locator('#session-list button').count(),3);assert.equal(await f.page.getByRole('button',{name:later.label,exact:true}).count(),1);
    const before=f.sent.length;await f.choose();assert.equal(f.sent.length,before,'clicking the selected session is a no-op');
    await f.page.locator('#text').fill('确认目标');await f.page.locator('#send').click();
    await f.page.waitForFunction(()=>document.querySelector('#status').textContent.includes('等待回复'));
    const input=f.sent.find(r=>r.path.endsWith('/chat/messages'));assert.equal(input.targetRef,'target-1');assert.equal(input.expectedBindingRevision,4);
  }finally{await f.context.close();}
});

test('lost switch response reads current selection without catalog reload or applying stale replay',async()=>{
  const f=await setup({navigation:(route,_body,reply,result,type)=>type==='session.select'?route.abort('failed'):reply(result),
    currentSelection:s=>({...s,targetRef:'target-2',revision:s.revision+1})});
  try{
    const baseline=f.sent.length;await f.page.getByRole('button',{name:first.label,exact:true}).click();
    await f.page.locator('#navigation-check').waitFor({state:'visible'});await f.page.locator('#navigation-check').click();
    await f.page.waitForFunction(()=>document.querySelector('#current').textContent==='真实会话 Beta'&&!document.querySelector('#workspace').disabled);
    const requests=f.sent.slice(baseline);assert.equal(requests.filter(r=>r.path.endsWith('/sessions/select')).length,1);
    assert.ok(requests.some(r=>r.path.endsWith('/selection/query')));
    assert.equal(requests.filter(r=>r.path.endsWith('/workspaces/query')||r.path.endsWith('/sessions/query')).length,0);
    assert.ok(f.lookups.every(p=>p.endsWith(requests.find(r=>r.path.endsWith('/sessions/select')).requestId)));
  }finally{await f.context.close();}
});

test('selection conflict reconciles a newer choice without reloading directories or sending',async()=>{
  const f=await setup({navigation:(_route,_body,reply,result,type)=>reply(type==='session.select'?
    {status:'rejected',error:{code:'BINDING_CONFLICT',message:'Selection changed'}}:result),
    currentSelection:s=>({...s,targetRef:'target-2',revision:s.revision+2})});
  try{
    const baseline=f.sent.length;await f.page.getByRole('button',{name:first.label,exact:true}).click();
    await f.page.waitForFunction(()=>document.querySelector('#current').textContent==='真实会话 Beta'&&!document.querySelector('#workspace').disabled);
    const requests=f.sent.slice(baseline);assert.equal(requests.filter(r=>r.path.endsWith('/sessions/select')).length,1);
    assert.equal(requests.filter(r=>r.path.endsWith('/selection/query')).length,1);
    assert.equal(requests.filter(r=>r.path.endsWith('/workspaces/query')||r.path.endsWith('/sessions/query')||r.path.endsWith('/chat/messages')).length,0);
    assert.match(await f.page.locator('#navigation-status').innerText(),/Selection changed/);
  }finally{await f.context.close();}
});
test('only authorized sessions appear and clicking one explicitly selects it without slash commands',async()=>{
  const f=await setup();try{
    assert.equal(await f.page.locator('#session-list button').count(),2);
    assert.equal(await f.page.locator('#text').isDisabled(),true);
    assert.equal(f.sent.some(s=>s.path.endsWith('/sessions/get')),false,'connection must not implicitly select the server binding');
    await f.choose();assert.equal(await f.page.locator('#current').innerText(),first.label);
    assert.equal(f.sent.find(s=>s.path.endsWith('/sessions/get')).targetRef,'target-1');
    assert.equal(await f.page.locator('#connection-dialog').isVisible(),false);
  }finally{await f.context.close();}
});

test('initial workspace is unselected and picker plus click submit shared typed CAS operations',async()=>{
  const f=await setup({chooseWorkspace:false});try{
    assert.equal(await f.page.locator('#workspace').inputValue(),'');
    assert.equal(await f.page.locator('#session-list button').count(),0);
    assert.equal(await f.page.locator('#session-count').innerText(),'已授权 1 个工作空间 · 请选择');
    assert.equal(await f.page.locator('#session-list').innerText(),'请先选择工作空间');
    assert.equal(await f.page.locator('#empty-title').innerText(),'选择一个工作空间');
    assert.match(await f.page.locator('#empty-description').innerText(),/选择工作空间/);
    assert.doesNotMatch(await f.page.locator('#empty-description').innerText(),/请在 CoWork/);
    assert.equal(await f.page.locator('#send').isDisabled(),true);
    await f.page.locator('#workspace').selectOption('sws-test');
    await f.page.waitForFunction(()=>!document.querySelector('#workspace').disabled);await f.choose();
    assert.equal(await f.page.locator('#session-count').innerText(),'当前工作空间 · 已授权 2 个会话');
    const changes=f.sent.filter(s=>['/workspaces/select','/sessions/select'].some(path=>s.path.endsWith(path)));
    assert.deepEqual(changes.map(s=>[s.path.split('/').slice(-2).join('/'),s.expectedBindingRevision]),[['workspaces/select',0],['sessions/select',1]]);
    assert.equal(new Set(f.sent.filter(s=>s.viewId).map(s=>s.viewId)).size,1);
    assert.ok(f.sent.every(s=>!s.operation));
  }finally{await f.context.close();}
});

test('lost switch response blocks sending and only looks up the original committed request',async()=>{
  const f=await setup({navigation:(route,_body,reply,result,type)=>type==='session.select'?route.abort('failed'):reply(result)});
  try{
    await f.page.getByRole('button',{name:first.label,exact:true}).click();
    await f.page.locator('#navigation-check').waitFor({state:'visible'});
    assert.equal(await f.page.locator('#send').isDisabled(),true);
    assert.equal(await f.page.locator('#workspace').isDisabled(),true);
    await f.page.locator('#navigation-check').click();
    await f.page.waitForFunction(()=>!document.querySelector('#send').disabled);
    assert.equal(await f.page.locator('#current').innerText(),first.label);
    assert.equal(f.sent.filter(s=>s.path.endsWith('/sessions/select')).length,1);
    assert.equal(f.sent.filter(s=>s.path.endsWith('/chat/messages')).length,0);
  }finally{await f.context.close();}
});

test('reload recovers server selection in the same tab without another selection write',async()=>{
  const f=await setup();try{
    await f.choose();const view=f.sent.find(s=>s.viewId).viewId;
    await f.page.reload();await f.page.locator('#entry').fill('web-test');await f.page.locator('#token').fill('test-token');await f.page.locator('#connect').click();
    await f.page.waitForFunction(()=>!document.querySelector('#send').disabled);
    assert.equal(await f.page.locator('#current').innerText(),first.label);
    assert.equal(f.sent.filter(s=>s.path.endsWith('/sessions/select')).length,1);
    assert.ok(f.sent.filter(s=>s.viewId).every(s=>s.viewId===view));
    assert.match(await f.page.locator('#history-status').innerText(),/已加载到最早/);
  }finally{await f.context.close();}
});
test('normal message uses clicked target and preserves slash text as chat content',async()=>{
  const f=await setup();try{await f.choose();await f.page.locator('#text').fill('/list 这是一条普通消息');await f.page.locator('#text').press('Enter');
    await f.page.waitForFunction(()=>document.querySelector('#status').textContent.includes('等待回复'));
    const send=f.sent.find(s=>s.path.endsWith('/chat/messages'));assert.equal(send.targetRef,'target-1');assert.equal(send.text,'/list 这是一条普通消息');
    assert.equal(await f.page.locator('#text').inputValue(),'');assert.equal(f.sent.some(s=>s.text?.startsWith('/use ')),false);
  }finally{await f.context.close();}
});
test('conversation switching isolates drafts and delayed replies by packet target',async()=>{
  let count=0;
  const f=await setup({poll:()=>({streamId:'stream-1',next:++count,messages:count===2?[{sequence:1,targetRef:'target-1',type:'message.ready',text:'Alpha 的延迟回复'}]:[]})});
  try{await f.choose();await f.page.locator('#text').fill('Alpha 草稿');await f.choose('target-2');
    assert.equal(await f.page.locator('#text').inputValue(),'');await f.page.locator('#text').fill('Beta 草稿');await f.page.waitForTimeout(2400);
    assert.equal(await f.page.locator('#log').innerText(),'');assert.match(await f.page.getByRole('button',{name:first.label,exact:true}).innerText(),/新消息/);
    await f.choose();assert.equal(await f.page.locator('#text').inputValue(),'Alpha 草稿');assert.match(await f.page.locator('#log').innerText(),/Alpha 的延迟回复/);
    await f.choose('target-2');assert.equal(await f.page.locator('#text').inputValue(),'Beta 草稿');assert.equal(await f.page.locator('#log').innerText(),'');
  }finally{await f.context.close();}
});
test('legacy waiting reports missing continuation capability and refreshes without sending',async()=>{
  let status='waiting';const f=await setup({snapshot:ref=>snapshot(ref,status)});
  try{await f.choose();await f.page.locator('#text').fill('/list 也是普通文本');assert.equal(await f.page.locator('#send').isDisabled(),true);
    assert.match(await f.page.locator('#execution-status').innerText(),/后端未提供续聊能力/);
    await f.page.locator('#text').press('Enter');assert.equal(f.sent.some(s=>s.path.endsWith('/chat/messages')),false);
    status='completed';await f.page.locator('#check').click();await f.page.waitForFunction(()=>!document.querySelector('#send').disabled);
    assert.equal(f.sent.some(s=>s.path.endsWith('/chat/messages')),false,'state recovery never auto sends the retained draft');
    await f.page.locator('#text').press('Enter');await f.page.waitForFunction(()=>document.querySelector('#status').textContent.includes('等待回复'));
  }finally{await f.context.close();}
});
test('missing and expired backend state cannot be presented as idle',async()=>{
  for(const execution of [null,{status:'completed',revision:17,observedAt:new Date(Date.now()-60000).toISOString()}]){
    const f=await setup({snapshot:ref=>({...snapshot(ref),execution})});try{await f.choose();await f.page.locator('#text').fill('保留');
      assert.equal(await f.page.locator('#send').isDisabled(),true);assert.match(await f.page.locator('#execution-status').innerText(),/尚未取得|已过期/);
    }finally{await f.context.close();}
  }
});
test('failed refresh clears an earlier ready observation and preserves the draft',async()=>{
  let fail=false;const f=await setup({statusReply:body=>fail?{status:'rejected',error:{message:'后端暂不可用'}}:{status:'completed',result:{type:'session.get',session:snapshot(body.targetRef)}}});
  try{await f.choose();await f.page.locator('#text').fill('保留');fail=true;await f.page.locator('#check').click();
    await f.page.waitForFunction(()=>document.querySelector('#execution-status').textContent.includes('尚未取得'));
    assert.equal(await f.page.locator('#send').isDisabled(),true);assert.equal(await f.page.locator('#text').inputValue(),'保留');
  }finally{await f.context.close();}
});
test('periodic state synchronization observes changes made in CoWork',async()=>{
  let status='waiting';const f=await setup({snapshot:ref=>snapshot(ref,status)});
  try{await f.choose();await f.page.locator('#text').fill('保留草稿');status='completed';
    await f.page.waitForFunction(()=>document.querySelector('#execution-status').textContent.includes('本轮已完成'),null,{timeout:16000});
    assert.equal(await f.page.locator('#send').isEnabled(),true);assert.equal(await f.page.locator('#text').inputValue(),'保留草稿');
    assert.equal(f.sent.some(s=>s.path.endsWith('/chat/messages')),false);
  }finally{await f.context.close();}
});
test('rejection after a state race keeps the draft and original request without resending',async()=>{
  const f=await setup({submit:(_r,_b,reply)=>reply({status:'rejected',error:{code:'UNAVAILABLE',message:'请在 CoWork 处理待办交互',retryable:false}})});
  try{await f.choose();await f.page.locator('#text').fill('继续');await f.page.locator('#send').click();await f.page.waitForFunction(()=>document.querySelector('#status').textContent.includes('待办交互'));
    assert.equal(await f.page.locator('#text').inputValue(),'继续');assert.match(await f.page.locator('#log').innerText(),/未发送/);assert.equal(f.sent.filter(s=>s.text==='继续').length,1);
  }finally{await f.context.close();}
});
test('uncertain send only looks up the original request',async()=>{
  const f=await setup({submit:route=>route.abort('failed')});try{await f.choose();await f.page.locator('#text').fill('只发送一次');await f.page.locator('#send').click();
    await f.page.getByRole('button',{name:'查询发送结果'}).waitFor();assert.equal(await f.page.locator('#text').inputValue(),'只发送一次');
    await f.page.getByRole('button',{name:'查询发送结果'}).click();await f.page.waitForFunction(()=>document.querySelector('#status').textContent.includes('等待回复'));
    assert.equal(f.sent.filter(s=>s.text==='只发送一次').length,1);
  }finally{await f.context.close();}
});
test('asynchronous admission followed by rejection preserves draft',async()=>{
  const f=await setup({submit:(_r,_b,reply)=>reply({status:'accepted',operationRef:'op-1'}),lookup:{status:'rejected',error:{message:'等待人工交互'}}});
  try{await f.choose();await f.page.locator('#text').fill('草稿不丢');await f.page.locator('#send').click();
    await f.page.waitForFunction(()=>document.querySelector('#status').textContent.includes('等待人工交互'),null,{timeout:5000});
    assert.equal(await f.page.locator('#text').inputValue(),'草稿不丢');assert.equal(f.sent.filter(s=>s.text==='草稿不丢').length,1);
  }finally{await f.context.close();}
});

test('agent acceptance without a task id confirms the original send and unlocks navigation',async()=>{
  const f=await setup({submit:(_r,_b,reply)=>reply({status:'accepted',acceptedBy:'channel',operationRef:'op-1'}),lookup:{status:'accepted',acceptedBy:'agent',operationRef:'op-1'}});
  try{await f.choose();await f.page.locator('#text').fill('只执行一次');await f.page.locator('#send').click();
    await f.page.waitForFunction(()=>document.querySelector('#status').textContent.includes('等待回复'),null,{timeout:5000});
    assert.equal(await f.page.locator('#text').inputValue(),'');await f.choose('target-2');
    assert.equal(f.sent.filter(s=>s.text==='只执行一次').length,1);
  }finally{await f.context.close();}
});
test('late agent acceptance refreshes authoritative execution state before allowing another send',async()=>{
  let confirmed=false;
  const f=await setup({submit:(_r,_b,reply)=>reply({status:'accepted',acceptedBy:'channel',operationRef:'op-1'}),
    lookup:()=>{confirmed=true;return{status:'accepted',acceptedBy:'agent',operationRef:'op-1'};},
    snapshot:ref=>snapshot(ref,confirmed?'running':'completed')});
  try{await f.choose();await f.page.locator('#text').fill('本轮只发送一次');await f.page.locator('#send').click();
    await f.page.waitForFunction(()=>document.querySelector('#execution-status').textContent.includes('正在运行'),null,{timeout:5000});
    assert.equal(await f.page.locator('#text').inputValue(),'');assert.equal(await f.page.locator('#send').isDisabled(),true);
    assert.equal(f.sent.filter(s=>s.text==='本轮只发送一次').length,1);
    await f.choose('target-2');
  }finally{await f.context.close();}
});
test('persistent inbox cursor continues after restart and leaves messages in their original conversation',async()=>{
  let count=0;const f=await setup({poll:()=>++count===1?{status:'page',nextCursor:'persistent-cursor',messages:[]}:
    {status:'page',nextCursor:'next-cursor',messages:count===2?[{sequence:'901',deliveryId:'restart-delivery',targetRef:'target-1',type:'message.ready',text:'重启后回复'}]:[]}});
  try{await f.choose();await f.page.getByText('重启后回复',{exact:true}).waitFor();assert.equal(f.polls[1].searchParams.get('cursor'),'persistent-cursor');assert.equal(f.polls[1].searchParams.has('after'),false);
  }finally{await f.context.close();}
});
test('empty authorized catalog explains configuration and exposes no stale message',async()=>{
  const f=await setup({empty:true,poll:()=>({streamId:'s',next:1,messages:[{sequence:1,targetRef:'target-1',type:'message.ready',text:'不可展示'}]})});
  try{assert.equal(await f.page.locator('#session-list button').count(),0);assert.equal(await f.page.locator('#send').isDisabled(),true);assert.match(await f.page.locator('#empty-description').innerText(),/CoWork.*授权/);assert.equal(await f.page.locator('#log').innerText(),'');
    assert.equal(await f.page.locator('#session-count').innerText(),'已授权 0 个会话');
    assert.equal(await f.page.locator('#session-list').innerText(),'暂无已授权会话');
  }finally{await f.context.close();}
});
test('pagination, search and revocation use only the current authorized catalog',async()=>{
  const pages={first:{items:[first],nextCursor:'20'},'20':{items:[second]}};const f=await setup({pages});
  try{await f.page.locator('#more-sessions').click();await f.page.waitForFunction(()=>document.querySelectorAll('#session-list button').length===2);
    await f.page.locator('#search').fill('Beta');assert.equal(await f.page.locator('#session-list button').count(),1);await f.page.locator('#search').fill('');
    await f.choose();pages.first={items:[]};await f.page.locator('#refresh').click();await f.page.waitForFunction(()=>document.querySelector('#current').textContent==='选择一个会话');
    assert.equal(await f.page.locator('#session-list button').count(),0);assert.equal(await f.page.locator('#send').isDisabled(),true);
  }finally{await f.context.close();}
});
test('accepted status query is looked up using its original request id',async()=>{
  let reads=0;const f=await setup({statusAccepted:true,lookup:()=>{reads++;return{status:'completed',result:{type:'session.get',session:snapshot()}};}});
  try{await f.choose();assert.equal(reads,1);assert.equal(f.sent.filter(s=>s.path.endsWith('/sessions/get')).length,1);
  }finally{await f.context.close();}
});
test('changing connection clears the prior user catalog and histories',async()=>{
  const f=await setup();try{await f.choose();await f.page.locator('#text').fill('旧身份草稿');await f.page.locator('#connection-settings').click();await f.page.locator('#entry').fill('other-entry');await f.page.locator('#connect').click();
    await f.page.waitForFunction(()=>document.querySelector('#session-count').textContent.includes('0 个'));
    assert.equal(await f.page.locator('#text').inputValue(),'');assert.equal(await f.page.locator('#log').innerText(),'');assert.equal(await f.page.locator('#session-list button').count(),0);
  }finally{await f.context.close();}
});
test('narrow viewport, keyboard focus, IME and Shift Enter remain usable',async()=>{
  const f=await setup({viewport:{width:390,height:844}});try{
    assert.ok(await f.page.locator('#session-list').evaluate(e=>e.clientHeight>=72),'workspace controls must leave a visible session list on narrow screens');
    await f.choose();await f.page.locator('#text').fill('你好');await f.page.locator('#text').press('Shift+Enter');assert.match(await f.page.locator('#text').inputValue(),/\n/);
    await f.page.locator('#text').dispatchEvent('keydown',{key:'Enter',isComposing:true});assert.equal(f.sent.some(s=>s.path.endsWith('/chat/messages')),false);
    assert.equal(await f.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await f.page.locator('#connection-settings').click();await f.page.getByRole('button',{name:'显示页面令牌'}).press('Enter');assert.equal(await f.page.locator('#token').getAttribute('type'),'text');
    await f.page.getByRole('button',{name:'关闭连接设置'}).click();assert.equal(await f.page.locator('#connection-settings').evaluate(e=>e===document.activeElement),true);
  }finally{await f.context.close();}
});

test('authorized history loads on selection and older pages prepend without duplicate messages',async()=>{
  const record=(id,role,text)=>({messageRef:id,role,text,createdAt:new Date().toISOString(),unsupportedContent:false});
  const f=await setup({history:(_route,body,reply)=>reply({targetRef:body.targetRef,
    items:body.cursor?[record('m1','user','旧提问'),record('m2','assistant','旧回复')]:[record('m3','user','新提问'),record('m4','assistant','新回复')],
    ...(body.cursor?{}:{nextCursor:'older'})})});
  try{await f.choose();await f.page.getByText('新提问',{exact:true}).waitFor();
    await f.page.locator('#text').fill('草稿保留');await f.page.locator('#history-more').click();
    await f.page.getByText('旧提问',{exact:true}).waitFor();
    assert.deepEqual(await f.page.locator('#log .body').allTextContents(),['旧提问','旧回复','新提问','新回复']);
    assert.equal(await f.page.locator('#text').inputValue(),'草稿保留');
    assert.equal(await f.page.locator('#history-more').isVisible(),false);
    assert.equal(f.sent.filter(s=>s.path.endsWith('/chat/messages')).length,0);
  }finally{await f.context.close();}
});
test('history capability denial leaves summary and draft usable without claiming empty history',async()=>{
  const f=await setup({history:route=>route.fulfill({status:403,contentType:'application/json',body:JSON.stringify({error:{code:'UNSUPPORTED_CAPABILITY'}})})});
  try{await f.choose();assert.match(await f.page.locator('#history-status').innerText(),/未开放历史正文/);
    assert.equal(await f.page.locator('#history-retry').isVisible(),false);
    assert.match(await f.page.locator('#execution-status').innerText(),/本轮已完成/);
    await f.page.locator('#text').fill('可以继续输入');assert.equal(await f.page.locator('#send').isEnabled(),true);
  }finally{await f.context.close();}
});
test('failed history load can retry and replayed live message uses the same stable message identity',async()=>{
  let attempts=0;
  const f=await setup({history:(route,body,reply)=>++attempts===1?route.abort('failed'):reply({targetRef:body.targetRef,
    items:[{messageRef:'stable-1',role:'assistant',text:'同一条回复',unsupportedContent:false,createdAt:new Date().toISOString()}]}),
    poll:()=>({streamId:'s',next:1,messages:[{sequence:1,targetRef:'target-1',messageRef:'stable-1',type:'message.ready',text:'同一条回复'}]})});
  try{await f.choose();await f.page.locator('#text').fill('仍是草稿');await f.page.locator('#history-retry').click();
    await f.page.waitForFunction(()=>document.querySelector('#history-status').textContent.includes('最早'));
    assert.equal(await f.page.getByText('同一条回复',{exact:true}).count(),1);
    assert.equal(await f.page.locator('#text').inputValue(),'仍是草稿');
  }finally{await f.context.close();}
});

test('backend user_turn availability enables ordinary continuation while retaining waiting status',async()=>{
  const f=await setup({snapshot:ref=>({...snapshot(ref,'waiting'),execution:{...snapshot(ref,'waiting').execution,
    rawStatus:'PAUSED',input:{allowed:true,mode:'continue',reason:'user_turn'}}})});
  try{await f.choose();assert.equal(await f.page.locator('#send').isEnabled(),true);
    assert.match(await f.page.locator('#execution-status').innerText(),/继续当前任务/);
    assert.equal(await f.page.locator('#execution-status').evaluate(n=>n.classList.contains('waiting')),false);
    await f.page.locator('#text').fill('继续说');await f.page.locator('#text').press('Enter');
    await f.page.waitForFunction(()=>document.querySelector('#status').textContent.includes('等待回复'));
    const messages=f.sent.filter(s=>s.path.endsWith('/chat/messages'));
    assert.equal(messages.length,1);assert.equal(messages[0].text,'继续说');
  }finally{await f.context.close();}
});
test('explicit backend interaction remains blocked and preserves the draft',async()=>{
  const f=await setup({snapshot:ref=>({...snapshot(ref,'waiting'),execution:{...snapshot(ref,'waiting').execution,
    rawStatus:'PAUSED_HITL',input:{allowed:false,mode:'blocked',reason:'interaction_required'}}})});
  try{await f.choose();await f.page.locator('#text').fill('approve');await f.page.locator('#text').press('Enter');
    assert.equal(await f.page.locator('#send').isDisabled(),true);
    assert.equal(await f.page.locator('#text').inputValue(),'approve');
    assert.match(await f.page.locator('#execution-status').innerText(),/审批或问题响应/);
    assert.equal(f.sent.some(s=>s.path.endsWith('/chat/messages')),false);
  }finally{await f.context.close();}
});


const interactivePacket=(kind='questions',ref='interaction-test',target='target-1')=>({type:'interaction.required',context:{targetRef:target},payload:{interactionRef:ref,revision:10,prompt:kind==='approval'?
  {kind,content:[{type:'text',text:'请批准安全操作'}],operation:{name:'fs:shell',arguments:{command:'echo safe'}},allowedResponses:['approve','reject']}:
  {kind,content:[{type:'text',text:'请回答问题组'}],questions:[
    {questionId:'q-a',content:[{type:'text',text:'选择方向'}],required:true,input:{type:'choice',multiple:false,allowOther:true,options:[{optionId:'opt-a',label:'继续',recommended:true},{optionId:'opt-b',label:'结束'}]}},
    {questionId:'q-b',content:[{type:'text',text:'补充说明'}],required:true,input:{type:'text'}}],allowedResponses:['answer']}}});
function interactionStatus(packets){return body=>({status:'completed',result:{type:'session.get',session:snapshot(body.targetRef,'waiting'),interactions:packets().filter(p=>p.context.targetRef===body.targetRef)}});}

test('pending recovery renders complete group without selecting recommendations and submits stable IDs together',async()=>{
  let packets=[interactivePacket()];const f=await setup({statusReply:interactionStatus(()=>packets),respond:(_route,body,reply)=>{packets=[];return reply({status:'completed',result:{type:'interaction.respond',interactionRef:body.interactionRef}});}});
  try{await f.choose();const card=f.page.locator('.interaction');await card.waitFor();
    assert.equal(await card.locator('input:checked').count(),0);
    await card.getByRole('button',{name:'提交整组答案'}).click();
    assert.match(await card.locator('[role=status]').innerText(),/全部必答/);assert.equal(f.sent.filter(r=>r.path.endsWith('/interactions/respond')).length,0);
    await card.getByLabel('结束',{exact:true}).check();await card.getByRole('textbox',{name:'补充说明',exact:true}).fill('测试整组');
    await card.getByRole('button',{name:'提交整组答案'}).click();await card.getByText('回应已生效',{exact:true}).waitFor();
    const operation=f.sent.find(r=>r.path.endsWith('/interactions/respond'));
    assert.equal(operation.interactionRef,'interaction-test');assert.equal(operation.expectedRevision,10);
    assert.deepEqual(operation.response,{kind:'answer',answers:[{questionId:'q-a',kind:'choice',optionIds:['opt-b']},{questionId:'q-b',kind:'text',content:[{partId:'answer',type:'text',text:'测试整组',format:'plain'}]}]});
    assert.equal(operation.operation,undefined);assert.equal(await card.getByRole('button',{name:'提交整组答案'}).isDisabled(),true);
  }finally{await f.context.close();}
});
test('approval click sends one canonical response and unknown outcomes only query original request',async()=>{
  const f=await setup({statusReply:interactionStatus(()=>[interactivePacket('approval')]),respond:(route)=>route.abort('failed'),lookup:{status:'unknown'}});
  try{await f.choose();const card=f.page.locator('.interaction');await card.getByRole('button',{name:'同意',exact:true}).click();
    await card.getByRole('button',{name:'查询回应结果'}).waitFor();
    assert.equal(await card.getByRole('button',{name:'同意',exact:true}).isDisabled(),true);
    await card.getByRole('button',{name:'查询回应结果'}).click();await f.page.waitForFunction(()=>!document.querySelector('#check').disabled);
    const writes=f.sent.filter(r=>r.path.endsWith('/interactions/respond'));assert.equal(writes.length,1);assert.deepEqual(writes[0].response,{kind:'approve'});
    assert.ok(f.lookups.some(p=>p.endsWith(writes[0].requestId)));
    await f.choose('target-2');await f.choose();assert.equal(await f.page.locator('.interaction').count(),1);
    assert.equal(await card.getByRole('button',{name:'同意',exact:true}).isDisabled(),true);
  }finally{await f.context.close();}
});
test('cross-client resolved pending disables old form while keeping unrelated session draft',async()=>{
  let packets=[interactivePacket('approval')];const f=await setup({statusReply:interactionStatus(()=>packets)});
  try{await f.choose();await f.page.locator('#text').fill('保留草稿');packets=[];
    await f.page.locator('#check').click();await f.page.getByText('事项已结束或已在其他客户端处理',{exact:true}).waitFor();
    assert.equal(await f.page.getByRole('button',{name:'同意',exact:true}).isDisabled(),true);
    assert.equal(await f.page.locator('#text').inputValue(),'保留草稿');assert.equal(f.sent.filter(r=>r.path.endsWith('/interactions/respond')).length,0);
  }finally{await f.context.close();}
});

test('terminal delivery after proven completion keeps the users own applied acknowledgement',async()=>{
  let done=false;const f=await setup({statusReply:interactionStatus(()=>done?[]:[interactivePacket('approval')]),
    respond:(_route,body,reply)=>{done=true;return reply({status:'completed',result:{type:'interaction.respond',interactionRef:body.interactionRef}});},
    poll:()=>({messages:done?[{sequence:1,targetRef:'target-1',type:'interaction.resolved',packet:{type:'interaction.resolved',context:{targetRef:'target-1'},payload:{interactionRef:'interaction-test',revision:11,outcome:'approved'}}}]:[],next:done?1:0,streamId:'stream-1'})});
  try{await f.choose();await f.page.getByRole('button',{name:'同意',exact:true}).click();
    await f.page.getByText('回应已生效',{exact:true}).waitFor();await f.page.waitForTimeout(2400);
    assert.equal(await f.page.locator('.interaction-feedback').innerText(),'回应已生效');assert.equal(f.sent.filter(r=>r.path.endsWith('/interactions/respond')).length,1);
  }finally{await f.context.close();}
});
test('a completion for a different interaction cannot be displayed as applied',async()=>{
  const f=await setup({statusReply:interactionStatus(()=>[interactivePacket('approval')]),respond:(_route,_body,reply)=>reply({status:'completed',result:{type:'interaction.respond',interactionRef:'another-interaction'}}),lookup:{status:'unknown'}});
  try{await f.choose();await f.page.getByRole('button',{name:'同意',exact:true}).click();
    await f.page.getByRole('button',{name:'查询回应结果'}).waitFor();assert.match(await f.page.locator('.interaction-feedback').innerText(),/尚未确认/);
    assert.equal(await f.page.getByRole('button',{name:'同意',exact:true}).isDisabled(),true);
  }finally{await f.context.close();}
});
test('reload restores an uncertain interaction request and only looks up its original ID',async()=>{
  const f=await setup({statusReply:interactionStatus(()=>[interactivePacket('approval')]),respond:route=>route.abort('failed'),lookup:{status:'unknown'}});
  try{await f.choose();await f.page.getByRole('button',{name:'同意',exact:true}).click();await f.page.getByRole('button',{name:'查询回应结果'}).waitFor();
    const original=f.sent.find(r=>r.path.endsWith('/interactions/respond'));
    await f.page.reload();await f.page.locator('#entry').fill('web-test');await f.page.locator('#token').fill('test-token');await f.page.locator('#connect').click();
    await f.page.getByRole('button',{name:'查询回应结果'}).waitFor();await f.page.getByRole('button',{name:'查询回应结果'}).click();
    assert.ok(f.lookups.some(p=>p.endsWith(original.requestId)));assert.equal(f.sent.filter(r=>r.path.endsWith('/interactions/respond')).length,1);
    assert.equal(await f.page.getByRole('button',{name:'同意',exact:true}).isDisabled(),true);
  }finally{await f.context.close();}
});
