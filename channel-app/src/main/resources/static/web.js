'use strict';
(() => {
  const $ = id => document.getElementById(id);
  // A fresh tab gets a fresh scope; a reload recovers this tab's existing scope.
  const reload=performance.getEntriesByType('navigation')[0]?.type==='reload';
  const viewId=reload&&sessionStorage.getItem('channel-view')||crypto.randomUUID();
  sessionStorage.setItem('channel-view',viewId);
  let restoredNavigation=null;
  try{const saved=JSON.parse(sessionStorage.getItem('channel-navigation'));if(saved?.viewId===viewId)restoredNavigation=saved;}catch(_){}
  const state = {entry:'',token:'',generation:0,after:0,stream:'',timer:null,online:false,busy:false,
    viewId,workspaces:[],workspace:null,selectionRevision:0,navigationPending:restoredNavigation,navigationChecking:false,
    selected:null,sessions:[],nextCursor:null,catalogReady:false,conversations:new Map(),pending:null,
    inboxCursor:null,consumerRef:null,gap:null,notifications:new Map(),completions:new Map(),seen:new Set(),deliveries:[],failures:0,lastStatusCheck:0,lastCatalogCheck:0};
  const show = (id,text,error=false) => {$(id).textContent=text;$(id).classList.toggle('error',error);};
  const session = ref => state.sessions.find(s=>s.targetRef===ref);
  const needsWorkspace = () => state.online&&state.catalogReady&&!state.workspace&&state.workspaces.length>0;
  function conversation(ref) {
    if (!state.conversations.has(ref)) state.conversations.set(ref,{draft:'',cards:[],unread:0,
      interactions:new Map(),historyLoaded:false,historyLoading:false,historyCursor:null,historyError:null,historyDenied:false});
    return state.conversations.get(ref);
  }
  function notice(text) {show('notice',text);$('notice').hidden=!text;}
  function connection(text,error=false) {show('connection',text,error);show('connection-feedback',text,error);}
  const labels = {idle:'空闲',running:'正在运行',waiting:'等待人工交互',completed:'本轮已完成',failed:'本轮失败',cancelled:'本轮已取消',interrupted:'执行已中断',unknown:'状态未知'};
  function executionLabel(execution) {
    return ({SUCCEEDED:'已就绪',COMPLETED:'已就绪',FINISHED:'已就绪',PAUSED:'已就绪',CANCELED:'已就绪',CANCELLED:'已就绪',
      PAUSED_HITL:'等待输入',RUNNING:'运行中',INTERRUPTED:'失败',FAILED:'失败'})[execution?.rawStatus]||labels[execution?.status]||'状态未知';
  }
  function executionHint() {
    if(state.navigationChecking) return '正在核对当前选择…';
    if(state.navigationPending) return '切换结果尚未确认，请查询原请求。';
    if(needsWorkspace()) return '请先选择工作空间，再选择会话。';
    if(!state.selected) return '请从左侧选择一个已授权会话。';
    const execution=session(state.selected)?.execution;
    if(!execution||!Number.isFinite(Date.parse(execution.observedAt))) return '尚未取得后端执行状态，请刷新状态后再发送。';
    if(Date.now()-Date.parse(execution.observedAt)>30000) return '后端执行状态已过期，正在重新同步。';
    if(execution.input) {
      if(execution.input.allowed) return '';
      return ({running:'会话正在运行，请等待本轮完成。',interaction_required:'等待处理待办 · 请在下方完成审批或问题响应，再继续对话。',
        resume_required:'执行已中断，请先在 CoWork 中恢复。',terminated:'会话执行容器已终止，无法继续发送。'})[execution.input.reason]||'输入状态暂不可用，请刷新状态。';
    }
    if(['idle','completed','failed','cancelled'].includes(execution.status)) return '';
    return ({waiting:'后端未提供续聊能力，请更新后端后刷新状态。',running:'会话正在运行，请等待本轮完成。',
      interrupted:'执行已中断，请先在 CoWork 中处理。'})[execution.status]||'后端执行状态未知，请重新查询。';
  }
  function controls() {
    for(const view of state.conversations.values())for(const card of view.interactions.values())interactionControls(card);
    $('workspace').disabled=!state.online||state.busy||!!state.pending||!!state.navigationPending||state.navigationChecking;
    for(const button of $('task-notifications').querySelectorAll('[data-source-navigation]'))button.disabled=$('workspace').disabled;
    $('navigation-check').hidden=!state.navigationPending;
    $('navigation-check').disabled=!state.online||state.busy;
    $('send').disabled=!state.online||state.busy||!!state.pending||!!executionHint();
    $('text').disabled=!state.online||!state.selected;
    $('text').placeholder=state.selected?'发送消息…':'先从左侧选择会话';
    $('refresh').disabled=!state.online||state.busy;
    $('check').disabled=!state.online||!state.selected||state.busy;
    $('search').disabled=!state.online;
    $('more-sessions').disabled=!state.online||state.busy;
    $('connection-settings').disabled=state.busy||!!state.pending||!!state.navigationPending;
    $('connect').disabled=state.busy||!!state.pending;
    for(const button of $('session-list').querySelectorAll('button')) button.disabled=state.busy||!!state.pending||!!state.navigationPending||state.navigationChecking||!session(button.dataset.target)?.available;
    const actual=session(state.selected),execution=actual?.execution,hint=executionHint();
    show('current',actual?.label||'选择一个会话');
    show('execution-status',hint||`${execution.input?.mode==='continue'?'已就绪 · 继续当前任务':execution.input?.mode==='deferred'?'提交后将等待子任务':executionLabel(execution)} · 更新于 ${new Date(execution.observedAt).toLocaleTimeString()}`);
    $('execution-status').classList.toggle('waiting',execution?.status==='waiting'&&!execution?.input?.allowed);
  }
  function renderSessions() {
    const filter=$('search').value.trim().toLocaleLowerCase();
    const buttons=state.sessions.filter(s=>s.label.toLocaleLowerCase().includes(filter)).map(s=>{
      const button=document.createElement('button');button.type='button';button.className='session';button.dataset.target=s.targetRef;
      button.setAttribute('aria-current',String(s.targetRef===state.selected));button.setAttribute('aria-label',s.label);
      const name=document.createElement('span');name.className='session-name';name.textContent=s.label;
      const meta=document.createElement('span');meta.className='session-state';
      const unread=state.conversations.get(s.targetRef)?.unread||0;
      meta.textContent=!s.available?'暂不可用':unread?`${unread} 条新消息`:s.execution?executionLabel(s.execution):'已授权';
      button.append(name,meta);button.addEventListener('click',()=>action(()=>selectSession(s.targetRef)));
      return button;
    });
    $('session-list').replaceChildren(...buttons);
    if(!buttons.length){const empty=document.createElement('p');empty.className='hint';empty.textContent=needsWorkspace()?'请先选择工作空间':filter?'没有匹配的会话':state.catalogReady?'暂无已授权会话':'连接后加载会话';$('session-list').append(empty);}
    show('session-count',needsWorkspace()?`已授权 ${state.workspaces.length} 个工作空间 · 请选择`:state.catalogReady?`${state.workspace?'当前工作空间 · ':''}已授权 ${state.sessions.length} 个会话${state.nextCursor?' · 还有更多':''}`:'连接后显示已授权会话');
    $('more-sessions').hidden=!state.nextCursor;controls();
  }
  function renderConversation() {
    refreshCompletions();
    const current=state.selected?conversation(state.selected):null;
    if(current) current.unread=0;
    $('log').replaceChildren(...(current?.cards.map(card=>card.box)||[]));
    $('empty').hidden=!!current?.cards.length;
    show('empty-title',state.selected?'开始对话':needsWorkspace()?'选择一个工作空间':state.online?'选择一个会话':'与 CoWork 继续对话');
    show('empty-description',state.selected?'历史记录加载后会显示在这里，也可以发送新消息。':needsWorkspace()?
      '从左侧选择工作空间，查看该空间内已授权的会话。':state.catalogReady&&!state.sessions.length?
      '请在 CoWork 的“设置 → Channel 接入”中授权会话，然后刷新。':'从左侧选择已授权的真实会话，消息将发送到该会话。');
    $('text').value=current?.draft||'';show('status','消息发送到当前选择的会话。');renderSessions();renderHistory();
  }
  function item(ref,metaText,text,user=false,messageRef=null,quiet=false) {
    const box=document.createElement('article');box.className='item'+(user?' user':'');
    const meta=document.createElement('div');meta.className='meta';meta.textContent=metaText;
    const body=document.createElement('div');body.className='body';body.textContent=text;box.append(meta,body);
    const card={box,meta,body,messageRef},view=conversation(ref);view.cards.push(card);

    if(ref===state.selected){$('empty').hidden=true;$('log').append(box);$('conversation').scrollTop=$('conversation').scrollHeight;}
    else if(!quiet)view.unread++;
    return card;
  }
  function renderHistory() {
    const view=state.selected?conversation(state.selected):null;
    $('history-controls').hidden=!view;
    if(!view){$('log').setAttribute('aria-busy','false');return;}
    show('history-status',view.historyLoading?'正在加载聊天记录…':view.historyError||
      (view.historyLoaded?(view.historyCursor?'向上加载更早的聊天记录。':'已加载到最早的聊天记录。'):''),!!view.historyError);
    $('history-more').hidden=!view.historyCursor||!!view.historyError;
    $('history-more').disabled=view.historyLoading;
    $('history-retry').hidden=!view.historyError||view.historyDenied;
    $('history-retry').disabled=view.historyLoading;
    $('log').setAttribute('aria-busy',String(view.historyLoading));
  }
  async function loadHistory(ref=state.selected,older=false) {
    if(!ref||!session(ref)?.available)return;
    const view=conversation(ref),generation=state.generation;
    if(view.historyLoading)return;
    view.historyLoading=true;view.historyError=null;if(state.selected===ref)renderHistory();
    const pane=$('conversation'),height=pane.scrollHeight,scroll=pane.scrollTop;
    try {
      const result=await request('sessions/history',{targetRef:ref,limit:20,...(older&&view.historyCursor?{cursor:view.historyCursor}:{})},generation);
      if(result.targetRef!==ref||!Array.isArray(result.items))throw new Error('聊天记录格式异常，请重试。');
      if(!session(ref)?.available)return;
      const previous=[...view.cards],known=new Set(previous.map(c=>c.messageRef).filter(Boolean)),added=[],alreadyLoaded=view.historyLoaded;
      view.historyLoaded=true;
      for(const message of result.items){
        if(!message.messageRef||known.has(message.messageRef))continue;
        known.add(message.messageRef);
        const caption=(message.role==='user'?'你':'CoWork')+' · '+new Date(message.createdAt).toLocaleString('zh-CN');
        added.push(item(ref,caption,message.text+
          (message.unsupportedContent?'\n[此消息包含图片或其他内容，请在 CoWork 查看。]':''),message.role==='user',message.messageRef,true));
      }
      view.cards=older||!alreadyLoaded?[...added,...previous]:[...previous,...added];
      view.historyCursor=result.nextCursor||null;view.historyDenied=false;
      if(state.selected===ref){renderConversation();pane.scrollTop=older?scroll+pane.scrollHeight-height:pane.scrollHeight;}
    } catch(error) {
      if(generation!==state.generation)return;
      view.historyDenied=error.status===401||error.status===403;
      if(error.status===400)view.historyCursor=null;
      view.historyError=error.status===401?'登录状态已失效，请重新登录 CoWork 并重新连接。':view.historyDenied?
        '当前入口或授权未开放历史正文；仍可查看获准的会话摘要。':error.status===400?
        '记录分页已失效，请重试重新加载。已有记录和草稿已保留。':'聊天记录加载失败，已有消息和草稿已保留，请重试。';
    } finally {view.historyLoading=false;if(generation===state.generation&&state.selected===ref)renderHistory();}
  }
  async function request(path,body,generation=state.generation) {
    if(generation!==state.generation)throw new Error('STALE_CONNECTION');
    const response=await fetch(`/web/${encodeURIComponent(state.entry)}/${path}`,{method:body?'POST':'GET',signal:AbortSignal.timeout(12000),
      headers:{Authorization:'Bearer '+state.token,...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
    const payload=await response.json();
    if(generation!==state.generation)throw new Error('STALE_CONNECTION');
    if(!response.ok){const error=new Error(response.status===401?'连接凭证失效，请重新连接。':payload.error?.message||`请求失败（HTTP ${response.status}），请稍后重试。`);error.status=response.status;throw error;}
    return payload;
  }
  async function navigationRequest(path,parameters,requestId=crypto.randomUUID()) {
    return request(path,{requestId,viewId:state.viewId,...parameters});
  }
  function applySelection(selection) {
    if(!selection||!Number.isSafeInteger(selection.revision))throw new Error('未取得服务端选择状态。');
    if(selection.revision<state.selectionRevision)throw new Error('选择结果已过期，请查询当前选择。');
    if(state.selected)conversation(state.selected).draft=$('text').value;
    state.workspace=selection.sessionWorkspaceRef||null;state.selected=selection.targetRef||null;state.selectionRevision=selection.revision;
  }
  async function navigationSnapshot() {
    const response=await navigationRequest('workspaces/query',{});
    if(response.status!=='completed'||response.result?.type!=='workspace.list')throw new Error(response.error?.message||'工作空间查询失败，请刷新。');
    state.workspaces=response.result.items;applySelection(response.result.selection);
    const placeholder=new Option('选择工作空间','');
    $('workspace').replaceChildren(placeholder,...state.workspaces.map(w=>new Option(w.label,w.sessionWorkspaceRef)));
    $('workspace').value=state.workspace||'';
  }
  function clearNavigation() {state.navigationPending=null;sessionStorage.removeItem('channel-navigation');show('navigation-status','');}
  async function finishNavigation(response,reconcile=false) {
    if(response.status==='completed'||response.status==='rejected') {
      const rejection=response.status==='rejected'?response.error?.message:null;
      let selection=response.result?.type==='session.select'?response.result:response.result?.selection;
      // A replay proves the write finished; a fresh lightweight read supplies today's selection.
      if(reconcile||rejection||selection?.revision<state.selectionRevision) {
        const current=await readOperation('selection/query',{});
        if(current.status!=='completed'||current.result?.type!=='selection.get')throw new Error('当前选择无法确认，请继续查询原请求。');
        selection=current.result.selection;
      }
      const previous=state.selected,previousWorkspace=state.workspace;
      applySelection(selection);$('workspace').value=state.workspace||'';clearNavigation();
      if(response.error?.code==='PERMISSION_DENIED'||state.workspace&&!state.workspaces.some(w=>w.sessionWorkspaceRef===state.workspace))await catalog();
      else if(previousWorkspace!==state.workspace){state.sessions=[];state.nextCursor=null;await sessionPage(false,previous);}
      else await displaySelection(previous);
      show('navigation-status',rejection||'已同步当前选择',!!rejection);return true;
    }
    show('navigation-status','切换结果未确认，请查询原请求。',true);return false;
  }
  async function checkNavigation() {
    const pending=state.navigationPending;if(!pending||state.navigationChecking)return;
    state.navigationChecking=true;
    try{await finishNavigation(await request('requests/'+encodeURIComponent(pending.requestId)),true);}
    finally{state.navigationChecking=false;controls();}
  }
  async function changeSelection(type,payload) {
    if(state.navigationPending)return;
    const pending={requestId:crypto.randomUUID(),viewId:state.viewId,entry:state.entry};
    state.navigationPending=pending;sessionStorage.setItem('channel-navigation',JSON.stringify(pending));controls();
    show('navigation-status','正在确认切换…');
    try{await finishNavigation(await navigationRequest(type,{...payload,expectedBindingRevision:state.selectionRevision},pending.requestId));}
    catch(error){if(state.navigationPending)show('navigation-status','切换结果未确认，继续查询原请求。',true);else show('status',error.message,true);}
    finally{controls();}
  }
  async function readOperation(path,body) {
    const generation=state.generation,requestId=crypto.randomUUID();
    let result=await navigationRequest(path,body,requestId);
    for(let attempt=0;result.status==='accepted'&&attempt<12;attempt++){
      await new Promise(resolve=>setTimeout(resolve,250));result=await request('requests/'+encodeURIComponent(requestId),null,generation);
    }
    return result;
  }
  async function observe(ref=state.selected) {
    if(!ref)return;
    const generation=state.generation;state.lastStatusCheck=Date.now();
    try{
      const result=await readOperation('sessions/get',{targetRef:ref});
      if(result.status!=='completed'||result.result?.type!=='session.get'||result.result.session.targetRef!==ref||!result.result.session.available)
        throw new Error(result.error?.message||'未取得会话状态，请稍后刷新。');
      const incoming=result.result.session;
      if(ref===state.selected&&incoming.workspace?.sessionWorkspaceRef!==state.workspace)throw new Error('会话所属工作空间已变化，请刷新目录。');
      if(ref===state.selected&&!session(ref))state.sessions.push(incoming);
      state.sessions=state.sessions.map(s=>s.targetRef!==ref?s:{...s,...incoming,
        execution:s.execution&&incoming.execution&&s.execution.revision>incoming.execution.revision?s.execution:incoming.execution});
      if(Array.isArray(result.result.interactions))recoverInteractions(ref,result.result.interactions);
      renderSessions();return result;
    }catch(error){
      if(generation===state.generation){state.sessions=state.sessions.map(s=>s.targetRef===ref?{...s,execution:null}:s);controls();}
      throw error;
    }
  }
  async function selectSession(ref) {
    if(!session(ref)?.available||state.selected===ref)return;
    if(state.selected)conversation(state.selected).draft=$('text').value;
    await changeSelection('sessions/select',{targetRef:ref});
    if(state.selected===ref)$('text').focus();
  }
  function receive(message) {
    if(!message.targetRef){notice('收到一条未标注会话的旧格式消息，请在 CoWork 中查看。');return;}
    if(!session(message.targetRef)){state.deliveries.push(message);return;}
    if(message.packet?.type?.startsWith('interaction.')){interactionEvent(message.packet);return;}
    if(message.type==='task.completed'&&message.deliveryId) {
      if(!state.completions.has(message.deliveryId))state.completions.set(message.deliveryId,{message,card:item(message.targetRef,'任务完成',message.text)});
      refreshCompletions();renderSessions();return;
    }
    const caption=message.type==='message.ready'?'CoWork':message.type==='task.failed'?'执行失败':'会话动态';
    const body=message.type==='task.waiting'?'本轮已暂停，请查看会话状态继续。':message.text;
    if(!message.messageRef||!conversation(message.targetRef).cards.some(c=>c.messageRef===message.messageRef))
      item(message.targetRef,caption,body,false,message.messageRef);
    refreshCompletions();renderSessions();
    if(message.targetRef===state.selected)state.lastStatusCheck=0;
  }

  function refreshCompletions() {
    for(const {message,card} of state.completions.values()) {
      const refs=message.packet?.payload?.resultMessageRefs||[];
      const available=new Set(conversation(message.targetRef).cards.map(c=>c.messageRef).filter(Boolean));
      card.body.textContent=refs.some(ref=>!available.has(ref))?'任务已完成，结果同步中…':'任务已完成。';
    }
  }
  async function markRead(ids) {
    if(ids.length&&state.consumerRef)await request('inbox/ack',{consumerRef:state.consumerRef,kind:'read',deliveryIds:ids});
  }
  function renderNotifications() {
    const region=$('task-notifications');
    const signature=JSON.stringify([...state.notifications.keys()])+!!state.showAllNotices;
    if(region.dataset.signature===signature)return;region.dataset.signature=signature;region.replaceChildren();
    const notices=[...state.notifications.values()];
    if(notices.length>1){const count=document.createElement('p');count.textContent=`有 ${notices.length} 个任务完成`;region.append(count);}
    if(notices.length>3&&!state.showAllNotices){const more=document.createElement('button');more.type='button';more.textContent='查看全部提醒';more.addEventListener('click',()=>{state.showAllNotices=true;renderNotifications();});region.append(more);}
    for(const message of state.showAllNotices?notices:notices.slice(-3)) {
      const box=document.createElement('article'),label=document.createElement('p'),open=document.createElement('button'),close=document.createElement('button');
      box.className='task-notice';label.textContent=['任务已完成',message.presentation?.source?.workspaceLabel,message.presentation?.source?.sessionLabel||message.sourceLabel,message.presentation?.source?.taskSummary].filter(Boolean).join(' · ');
      open.type=close.type='button';open.textContent='查看原会话';close.textContent='关闭提醒';
      open.dataset.sourceNavigation='';open.disabled=$('workspace').disabled;
      open.addEventListener('click',()=>action(async()=>{
        const result=await readOperation('sessions/get',{targetRef:message.targetRef});
        const target=result.result?.session;
        if(result.status!=='completed'||!target?.available||target.targetRef!==message.targetRef)throw new Error('原会话已不可访问，请刷新授权。');
        const workspace=target.workspace?.sessionWorkspaceRef;
        if(!workspace)throw new Error('无法确认原工作空间，请刷新会话目录。');
        if(state.workspace!==workspace)await changeSelection('workspaces/select',{sessionWorkspaceRef:workspace});
        if(state.navigationPending||state.workspace!==workspace)return;
        if(!session(target.targetRef))state.sessions.push(target);
        await selectSession(target.targetRef);
        if(state.selected!==target.targetRef)return;
        state.notifications.delete(message.deliveryId);renderNotifications();await markRead([message.deliveryId]);
        const completion=state.completions.get(message.deliveryId);completion?.card.box.scrollIntoView({block:'center'});
      }));
      close.addEventListener('click',()=>{state.notifications.delete(message.deliveryId);renderNotifications();});
      box.append(label,open,close);region.append(box);
    }
  }

  const contentText=parts=>(parts||[]).map(p=>p.type==='text'?p.text:'[此内容请在 CoWork 查看]').join('\n');
  function interactionStorage(card,value) {
    const key='channel-interaction:'+state.entry+':'+state.viewId+':'+card.ref;
    if(value===undefined){try{return JSON.parse(sessionStorage.getItem(key));}catch(_){return null;}}
    if(value===null)sessionStorage.removeItem(key);else sessionStorage.setItem(key,JSON.stringify(value));
  }
  function interactionControls(card) {
    const disabled=!state.online||card.closed||!!card.pending||state.busy;
    for(const field of card.form.querySelectorAll('input,textarea,button'))field.disabled=disabled;
    card.lookup.disabled=!state.online||state.busy;card.lookup.hidden=!card.pending;
  }
  function closeInteraction(card,label) {
    card.closed=true;card.feedback.textContent=card.applied?'回应已生效':label;interactionControls(card);
    // A terminal fact from another client does not prove our unknown write succeeded.
    if(card.pending)card.feedback.textContent+='；本次提交仍需查询原请求。';
  }
  function interactionEvent(packet) {
    const target=packet.context?.targetRef,p=packet.payload;
    if(!target||!p?.interactionRef||!Number.isSafeInteger(p.revision))return;
    const view=conversation(target),existing=view.interactions.get(p.interactionRef);
    if(existing&&existing.revision>p.revision)return;
    if(packet.type!=='interaction.required') {
      if(existing){existing.revision=p.revision;closeInteraction(existing,({'interaction.resolved':'事项已处理','interaction.cancelled':'事项已取消','interaction.expired':'事项已过期'})[packet.type]||'事项已结束');}
      return;
    }
    if(existing&&existing.revision===p.revision)return;
    if(existing)closeInteraction(existing,'事项版本已更新，请处理新表单。');
    const card=item(target,p.prompt.kind==='approval'?'CoWork · 请求批准':'CoWork · 需要回答',contentText(p.prompt.content),false,null,true);
    Object.assign(card,{ref:p.interactionRef,target,revision:p.revision,prompt:p.prompt,closed:false,pending:null});
    card.box.classList.add('interaction');card.box.dataset.interactionRef=card.ref;
    const form=document.createElement('form');card.form=form;form.className='interaction-form';
    if(p.prompt.kind==='approval') {
      const operation=document.createElement('p');operation.textContent='操作：'+(p.prompt.operation?.name||'请求批准');form.append(operation);
      if(p.prompt.operation?.arguments){const args=document.createElement('pre');args.textContent=JSON.stringify(p.prompt.operation.arguments,null,2);form.append(args);}
      for(const [kind,label] of [['approve','同意'],['reject','拒绝']])if(p.prompt.allowedResponses.includes(kind)) {
        const button=document.createElement('button');button.type='submit';button.name='decision';button.value=kind;button.textContent=label;form.append(button);
      }
    } else for(const question of p.prompt.questions) {
      const group=document.createElement('fieldset'),legend=document.createElement('legend');group.dataset.question=question.questionId;
      legend.textContent=contentText(question.content)+(question.required?'（必答）':'');group.append(legend);
      if(question.input.type==='choice') {
        for(const option of question.input.options) {
          const label=document.createElement('label'),input=document.createElement('input'),text=document.createElement('span');
          input.type=question.input.multiple?'checkbox':'radio';input.name=card.ref+':'+question.questionId;input.value=option.optionId;
          text.textContent=option.label+(option.recommended?'（推荐）':'')+(option.description?' · '+option.description:'');label.append(input,text);group.append(label);
        }
        if(question.input.allowOther) {
          const label=document.createElement('label'),input=document.createElement('input'),text=document.createElement('span');
          input.type=question.input.multiple?'checkbox':'radio';input.name=card.ref+':'+question.questionId;input.value='__other';text.textContent='其他';label.append(input,text);group.append(label);
          const other=document.createElement('input');other.type='text';other.dataset.other='true';other.setAttribute('aria-label',contentText(question.content)+' · 其他答案');group.append(other);
        }
      } else {const input=document.createElement('textarea');input.setAttribute('aria-label',contentText(question.content));group.append(input);}
      form.append(group);
    }
    if(p.prompt.kind==='questions'&&p.prompt.allowedResponses.includes('answer')){const button=document.createElement('button');button.type='submit';button.textContent='提交整组答案';form.append(button);}
    const feedback=document.createElement('p');feedback.className='interaction-feedback';feedback.setAttribute('role','status');card.feedback=feedback;
    const lookup=document.createElement('button');lookup.type='button';lookup.textContent='查询回应结果';card.lookup=lookup;lookup.hidden=true;
    lookup.addEventListener('click',()=>action(()=>lookupInteraction(card)));
    card.box.append(form,feedback,lookup);view.interactions.set(card.ref,card);
    const saved=interactionStorage(card);if(saved){card.pending=saved;feedback.textContent='此前回应结果待确认，请查询原请求。';}
    if(p.expiresAt&&Date.parse(p.expiresAt)<=Date.now())closeInteraction(card,'事项已过期');
    form.addEventListener('submit',event=>{event.preventDefault();if(card.pending||card.closed)return;
      try {
        const response=p.prompt.kind==='approval'?{kind:event.submitter?.value}:collectAnswers(card);
        if(!p.prompt.allowedResponses.includes(response.kind))throw new Error('请选择允许的回应。');
        action(()=>submitInteraction(card,response));
      }catch(error){feedback.textContent=error.message;feedback.classList.add('error');}
    });
    interactionControls(card);
  }
  function collectAnswers(card) {
    const answers=[];
    for(const q of card.prompt.questions) {
      const group=[...card.form.querySelectorAll('fieldset')].find(g=>g.dataset.question===q.questionId);
      if(q.input.type==='choice') {
        const checked=[...group.querySelectorAll('input:checked')].map(i=>i.value),other=checked.includes('__other')?group.querySelector('[data-other]').value.trim():null;
        if(!checked.length){if(!q.required)continue;throw new Error('请回答全部必答题。');}
        if(checked.includes('__other')&&!other)throw new Error('请填写其他答案。');
        answers.push({questionId:q.questionId,kind:'choice',optionIds:checked.filter(id=>id!=='__other'),...(other?{otherText:other}:{})});
      } else {
        const text=group.querySelector('textarea').value.trim();if(!text){if(!q.required)continue;throw new Error('请回答全部必答题。');}
        answers.push({questionId:q.questionId,kind:'text',content:[{partId:'answer',type:'text',text,format:'plain'}]});
      }
    }
    return {kind:'answer',answers};
  }
  function interactionOutcome(card,result) {
    if(result.status==='completed'&&result.result?.type==='interaction.respond'&&result.result.interactionRef===card.ref) {
      card.pending=null;card.applied=true;interactionStorage(card,null);closeInteraction(card,'回应已生效');return true;
    }
    if(result.status==='rejected') {
      card.pending=null;interactionStorage(card,null);
      card.feedback.textContent=result.error?.message||'回应未受理，请刷新待办。';card.feedback.classList.add('error');
      if(['INTERACTION_STALE','PERMISSION_DENIED','AUTH_REQUIRED'].includes(result.error?.code))card.closed=true;
      interactionControls(card);return true;
    }
    card.feedback.textContent='回应结果尚未确认，请查询原请求。';interactionControls(card);return false;
  }
  async function submitInteraction(card,response) {
    if(card.pending||card.closed)return;
    const pending={id:crypto.randomUUID(),revision:card.revision};card.pending=pending;
    interactionStorage(card,pending);card.feedback.classList.remove('error');card.feedback.textContent='正在提交回应…';interactionControls(card);
    try {
      const result=await navigationRequest('interactions/respond',{interactionRef:card.ref,expectedRevision:pending.revision,response},pending.id);
      interactionOutcome(card,result);
    }catch(error){card.feedback.textContent='回应结果尚未确认，请查询原请求。';}
    interactionControls(card);
    try{await observe(card.target);}catch(_){}
  }
  async function lookupInteraction(card) {
    if(!card.pending)return;
    try{interactionOutcome(card,await request('requests/'+encodeURIComponent(card.pending.id)));}
    catch(error){card.feedback.textContent='查询暂时失败，原请求已保留，请重试查询。';}
    interactionControls(card);
  }
  function recoverInteractions(target,packets) {
    const present=new Set();
    for(const packet of packets)if(packet.context?.targetRef===target){present.add(packet.payload.interactionRef);interactionEvent(packet);}
    for(const card of conversation(target).interactions.values())if(!present.has(card.ref)&&!card.closed)closeInteraction(card,'事项已结束或已在其他客户端处理');
  }

  async function catalog(append=false) {
    const previous=state.selected;state.lastCatalogCheck=Date.now();
    if(!append)await navigationSnapshot();
    return sessionPage(append,previous);
  }
  async function sessionPage(append,previous) {
    const cursor=append?state.nextCursor:null;
    if(!state.workspace){state.sessions=[];state.nextCursor=null;state.catalogReady=true;renderConversation();return;}
    const result=await readOperation('sessions/query',{sessionWorkspaceRef:state.workspace,limit:20,...(cursor?{cursor}:{})});
    if(result.status!=='completed'||result.result?.type!=='session.list')throw new Error(result.error?.message||'会话列表加载失败，请刷新。');
    const page=result.result;
    if(append&&page.nextCursor&&page.nextCursor===cursor)throw new Error('会话分页没有前进，请刷新。');
    state.sessions=append?Array.from(new Map([...state.sessions,...page.items].map(s=>[s.targetRef,s])).values()):
      page.items.map(s=>({...s,execution:s.execution||session(s.targetRef)?.execution}));
    state.nextCursor=page.nextCursor||null;state.catalogReady=true;
    if(state.selected&&!session(state.selected)) {
      // A recovered selection can be on a later page. Verify it, never replace it with page one.
      const selected=await readOperation('sessions/get',{targetRef:state.selected});
      if(selected.status!=='completed'||selected.result?.session?.workspace?.sessionWorkspaceRef!==state.workspace)
        throw new Error('当前选择无法确认，请刷新工作空间。');
      state.sessions.push(selected.result.session);
    }
    await displaySelection(previous);
    return result;
  }
  async function displaySelection(previous) {
    // Other workspaces retain their own drafts and delayed replies.
    const queued=state.deliveries;state.deliveries=[];queued.forEach(receive);
    renderConversation();
    if(state.selected&&previous!==state.selected){await observe();if(!conversation(state.selected).historyLoaded)await loadHistory();}
  }
  function outcome(result,pending) {
    const {card,ref}=pending;
    if(result.status==='rejected'){
      card.meta.textContent='你 · 未发送';card.body.textContent=pending.text+'\n'+(result.error?.message||'请求未被受理');card.box.classList.add('error');
      if(state.selected===ref)show('status',result.error?.message||'请求未被受理',true);
      return true;
    }
    if(result.status==='accepted'||result.status==='completed'){
      card.meta.textContent=result.status==='completed'?'你 · 已发送':'你 · 已受理';
      const settled=result.status==='completed'||result.acceptedBy==='agent'||!!result.taskRef;
      if(settled&&conversation(ref).draft===pending.text)conversation(ref).draft='';
      if(state.selected===ref){if(settled&&$('text').value===pending.text)$('text').value='';show('status',settled?'消息已受理，正在等待回复。':'消息已接收，正在确认发送结果。');}
      return settled;
    }
    card.meta.textContent='你 · 发送结果待确认';
    if(state.selected===ref)show('status','发送结果未确认，请查询原请求，避免重复发送。',true);
    return false;
  }
  async function lookup(pending) {
    const result=await request('requests/'+encodeURIComponent(pending.id));
    if(outcome(result,pending)){
      try{await observe(pending.ref);}catch(_){/* An unavailable observation keeps sending disabled. */}
      if(state.pending===pending)state.pending=null;pending.button?.remove();
    }
    controls();
  }
  function queryButton(pending) {
    if(pending.button)return;
    const button=document.createElement('button');button.type='button';button.textContent='查询发送结果';
    button.addEventListener('click',()=>action(()=>lookup(pending)));pending.card.box.append(button);pending.button=button;
  }
  async function poll(generation) {
    if(generation!==state.generation)return;
    try{
      if(state.gap)return;
      const result=await request(`inbox?consumerKey=${encodeURIComponent(state.viewId)}${state.inboxCursor?'&cursor='+encodeURIComponent(state.inboxCursor):''}`,null,generation);
      if(result.status==='gap') {
        state.gap=result.resyncCursor;$('inbox-gap').hidden=false;$('inbox-resync').disabled=false;
        state.online=true;state.failures=0;connection('已连接，部分收件需要重新同步。');controls();return;
      }
      if(result.consumerRef)state.consumerRef=result.consumerRef;
      // This view has no durable local message cache: it never sends a received acknowledgement.
      // Reload replays the server inbox, while read acknowledgements only record actual viewing.
      if(state.stream&&state.stream!==result.streamId)notice('连接已恢复。服务已重启，刷新页面可以重新加载聊天记录。');
      state.stream=result.streamId||'';state.after=result.next??state.after;
      for(const message of result.messages||[]){const key=message.deliveryId||state.stream+':'+message.sequence;
        if(message.sequence!=null&&state.seen.has(key))continue;
        if(message.sequence!=null)state.seen.add(key);
        receive(message);
        if(message.presentation?.notification?.kind==='task_completed'&&!message.readAt&&message.targetRef!==state.selected)state.notifications.set(message.deliveryId,message);
      }
      renderNotifications();
      const readIds=(result.messages||[]).filter(m=>m.deliveryId&&m.targetRef===state.selected&&document.visibilityState==='visible').map(m=>m.deliveryId);
      if(readIds.length)await markRead(readIds);
      state.inboxCursor=result.nextCursor||state.inboxCursor;
      state.online=true;state.failures=0;connection('已连接');controls();
      if(state.navigationPending&&!state.busy)try{await checkNavigation();}catch(_){/* Never resubmit a switch. */}
      if(state.pending)try{await lookup(state.pending);}catch(_){/* Keep the original request available for lookup. */}
      if(!state.busy)for(const view of state.conversations.values())for(const card of view.interactions.values())
        if(card.pending)await lookupInteraction(card);
      if(state.selected&&!state.busy&&!state.pending&&Date.now()-state.lastStatusCheck>=10000)
        try{await observe();}catch(error){show('status',error.message,true);}
      if(state.catalogReady&&!state.nextCursor&&!state.busy&&!state.pending&&!state.navigationPending&&Date.now()-state.lastCatalogCheck>=30000)
        try{await catalog();}catch(error){notice(error.message);}
    }catch(error){
      if(generation!==state.generation)return;
      state.online=false;state.failures++;connection(error.status===401?error.message:'连接中断，消息和草稿已保留。正在恢复…',true);controls();
      if(error.status===401||state.failures>=5)return;
    }
    if(generation===state.generation)state.timer=setTimeout(()=>poll(generation),Math.min(30000,2000*2**state.failures));
  }
  async function action(work) {
    if(state.busy)return;state.busy=true;controls();
    try{await work();}catch(error){show('status',error.message,true);}finally{state.busy=false;controls();}
  }
  $('connection-settings').addEventListener('click',()=>$('connection-dialog').showModal());
  $('close-settings').addEventListener('click',()=>$('connection-dialog').close());
  $('toggle').addEventListener('click',()=>{const visible=$('token').type==='password';$('token').type=visible?'text':'password';$('toggle').textContent=visible?'隐藏':'显示';$('toggle').setAttribute('aria-label',visible?'隐藏页面令牌':'显示页面令牌');$('toggle').setAttribute('aria-pressed',String(visible));});
  $('settings').addEventListener('submit',async event=>{
    event.preventDefault();if(state.busy||state.pending)return;
    const entry=$('entry').value.trim(),token=$('token').value;
    if(!entry||!token){const field=!entry?$('entry'):$('token');field.setAttribute('aria-invalid','true');field.focus();connection('请填写接入口和页面令牌。',true);return;}
    $('entry').removeAttribute('aria-invalid');$('token').removeAttribute('aria-invalid');
    if(state.selected)conversation(state.selected).draft=$('text').value;
    const changed=entry!==state.entry||token!==state.token;
    clearTimeout(state.timer);state.generation++;const generation=state.generation;
    if(changed){state.conversations.clear();state.seen.clear();state.deliveries=[];state.notifications.clear();state.completions.clear();renderNotifications();}state.inboxCursor=null;state.consumerRef=null;state.gap=null;$('inbox-gap').hidden=true;
    Object.assign(state,{entry,token,online:false,selected:null,workspace:null,workspaces:[],selectionRevision:0,sessions:[],nextCursor:null,catalogReady:false,stream:'',after:0,failures:0});
    notice('');renderConversation();connection('正在连接…');
    await poll(generation);
    if(generation===state.generation&&state.online){$('connection-dialog').close();await action(()=>catalog());}
  });
  new ResizeObserver(entries=>document.documentElement.style.setProperty('--composer-height',entries[0].contentRect.height+'px')).observe(document.querySelector('.composer-wrap'));
  $('inbox-resync').addEventListener('click',()=>{if(!state.gap)return;state.inboxCursor=state.gap;state.gap=null;$('inbox-gap').hidden=true;$('inbox-resync').disabled=true;clearTimeout(state.timer);poll(state.generation);});
  $('workspace').addEventListener('change',()=>{const ref=$('workspace').value;$('workspace').value=state.workspace||'';if(ref)action(()=>changeSelection('workspaces/select',{sessionWorkspaceRef:ref}));});
  $('navigation-check').addEventListener('click',()=>action(checkNavigation));
  $('refresh').addEventListener('click',()=>action(async()=>{await catalog();if(state.selected)await observe();}));
  $('more-sessions').addEventListener('click',()=>action(()=>catalog(true)));
  $('search').addEventListener('input',renderSessions);
  $('check').addEventListener('click',()=>action(()=>observe()));
  $('history-more').addEventListener('click',()=>loadHistory(state.selected,true));
  $('history-retry').addEventListener('click',()=>loadHistory(state.selected,!!conversation(state.selected)?.historyCursor));
  $('text').addEventListener('input',()=>{if(state.selected)conversation(state.selected).draft=$('text').value;controls();});
  $('text').addEventListener('keydown',event=>{if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();$('compose').requestSubmit();}});
  $('compose').addEventListener('submit',event=>{
    event.preventDefault();if(!state.online||state.busy||state.pending||state.navigationPending||state.navigationChecking||!state.selected)return;
    const text=$('text').value,hint=executionHint();
    if(!text.trim()){$('text').setAttribute('aria-invalid','true');$('text').focus();show('status','请输入消息。',true);return;}
    if(hint){show('status',hint,true);return;}
    $('text').removeAttribute('aria-invalid');
    action(async()=>{
      const generation=state.generation,ref=state.selected;
      const pending={id:crypto.randomUUID(),ref,text,card:item(ref,'你 · 发送中',text,true)};
      show('status','正在发送…');
      try{
        const result=await navigationRequest('chat/messages',{targetRef:ref,expectedBindingRevision:state.selectionRevision,text},pending.id);
        if(!outcome(result,pending)){state.pending=pending;queryButton(pending);}
        try{await observe(ref);}catch(_){/* Keep the original receipt and draft visible. */}
      }catch(error){
        if(generation!==state.generation)return;
        pending.card.meta.textContent='你 · 发送结果待确认';state.pending=pending;queryButton(pending);
        show('status','发送结果未确认，草稿已保留。请查询原请求，避免重复发送。',true);
      }
    });
  });
  $('close-settings').disabled=false;$('toggle').disabled=false;
  renderSessions();$('connection-dialog').showModal();
})();
