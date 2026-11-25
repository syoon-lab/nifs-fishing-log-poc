'use strict';
const $ = id => document.getElementById(id);
const el = (tag, text, cls) => {
  const e = document.createElement(tag);
  if (text != null) e.textContent = text;
  if (cls) e.className = cls;
  return e;
};
const fmt = x => x == null ? '미확인' : new Date(x).toLocaleString('ko-KR', {timeZone: 'Asia/Seoul', hour12: false});
const names = {record_class:'자료 구분',target_species:'목표 어종',gear_quantity:'어구 수량 (이전 기록)',notes_state:'특이사항 언급',notes_text:'특이사항 원문·확인 내용',work_type:'작업 내용',gear_type:'어법',deployment_date:'확인한 투망일',haul_quantity:'양망 수량',redeploy_quantity:'재투망 수량',gear_unit:'어구 단위',disposition:'작업 후 처리',workers:'작업 인원 (이전 기록)',outcome:'어획 유무',notes:'PC 검토 메모',species:'어종',quantity:'수량',unit:'단위',weight_kg:'중량 (kg)',approximate:'근삿값'};
const options = {record_class:[['UNCLASSIFIED','미확인'],['TEST','실내·개발 시험'],['FIELD','현장 실측']],notes_state:[['NOT_MENTIONED','언급 없음'],['EXPLICIT_NONE','없다고 확인'],['REPORTED','내용 있음']],gear_type:[['','미확인'],['POT','통발'],['GILLNET','자망'],['OTHER','기타']],disposition:[['UNCONFIRMED','미확인'],['REMOVED','최종 철거'],['REDEPLOYED','재투망']],outcome:[['UNCONFIRMED','미확인'],['CAUGHT','어획 있음'],['NO_CATCH','어획 없음'],['NOT_FISHED','미조업']]};
const fields = ['record_class','work_type','target_species','gear_quantity','notes_state','notes_text','gear_type','deployment_date','haul_quantity','redeploy_quantity','gear_unit','disposition','workers','outcome','notes'];
const numeric = new Set(['haul_quantity','redeploy_quantity','workers','gear_quantity','quantity','weight_kg']);
const runNames = {QUEUED:'대기 중',RUNNING:'음성 읽는 중',COMPLETED:'음성 읽기 완료',FAILED:'확인 필요',INTERRUPTED:'중단'};
const recordingNames = {COMPLETED:'완료',FAILED:'실패',INTERRUPTED:'중단',RUNNING:'처리 중',CANCELLED:'취소',PENDING:'대기 중'};
const phaseNames = {WORK:'작업 녹음',START:'시작 녹음',END:'마무리 녹음',NOTE:'추가 녹음',LEGACY:'이전 기록'};
let overview = null, active = null, preview = null, catchRows = [], dirty = false, loadSeq = 0;
let processing = null, processingTimer = null, processingSignature = '', processingBusy = false, dialectChoice = 'base', dialectUserChoice = false;
const pcContainers = new Map();
const transcriptEdits = new Map(), transcriptEditContainers = new Map();
const transcriptSelectionNames = {base:'기본 방식으로 읽은 문장',dialect:'방언을 반영한 문장',corrected:'직접 고친 문장'};
async function api(path, body) {
  const r = await fetch(path, body ? {method:'POST',headers:{'Content-Type':'application/json','X-Local-Import':'1'},body:JSON.stringify(body)} : {});
  const j = await r.json();
  if (!r.ok) throw Error(j.error || '요청 실패');
  return j;
}
function message(id, text, error = false) {
  const node = $(id);
  if (!node) return;
  node.textContent = text;
  node.classList.toggle('error', error);
}
function parse(s) { try { return JSON.parse(s); } catch { return s; } }
function labelValue(k, v) { return options[k]?.find(pair => pair[0] === v)?.[1] ?? (v == null || v === '' ? '미확인' : String(v)); }
function hasTranscriptContent(text) { return typeof text==='string' && /[\p{L}\p{N}]/u.test(text); }
function isReviewedRun(run) { return run.result?.text_source==='reviewed' || Boolean(run.input?.transcript_review); }
function originalTranscripts(run) {
  return isReviewedRun(run)?run.input?.transcript_parent || {}:
    {asr_text:run.asr_text,dialect_text:run.dialect_text,dialect_error:run.result?.dialect_error};
}
function savedTranscriptReview(run) { return run.input?.transcript_review ?? run.result?.transcript_review; }
function reviewedTranscript(run) {
  const result=run.result, review=savedTranscriptReview(run), text=result?.reviewed_text, original=originalTranscripts(run);
  if(result?.text_source!=='reviewed' || !review || text!==review.text || !hasTranscriptContent(text)) return null;
  if(review.selection==='base' && text!==original.asr_text) return null;
  if(review.selection==='dialect' && (text!==original.dialect_text || original.dialect_error)) return null;
  return Object.hasOwn(transcriptSelectionNames,review.selection)?text:null;
}
function usesBaseFallback(run) {
  return run.result?.text_source==='base_fallback' && hasTranscriptContent(run.asr_text) &&
    (Boolean(run.result?.dialect_error) || !hasTranscriptContent(run.dialect_text));
}
function needsExtractionReview(run) {
  if(run.status!=='FAILED') return false;
  if(isReviewedRun(run)) return hasTranscriptContent(savedTranscriptReview(run)?.text);
  if(run.result?.text_source==='dialect') return hasTranscriptContent(run.dialect_text) && !run.result?.dialect_error;
  if(run.result?.text_source==='base') return hasTranscriptContent(run.asr_text);
  if(run.result?.text_source==='base_fallback') return usesBaseFallback(run);
  const dialectExpected=Boolean(run.model_info?.dialect || run.model_info?.config?.asr?.dialect);
  return hasTranscriptContent(run.asr_text) && (!dialectExpected || hasTranscriptContent(run.dialect_text));
}
function runLabel(run) {
  if(isReviewedRun(run)) {
    if(run.status==='COMPLETED') return '검토 전사로 추출 완료';
    if(run.status==='QUEUED') return '검토 전사 · 추출 대기';
    if(run.status==='RUNNING') return '검토 전사 · 추출 중';
    if(needsExtractionReview(run)) return '검토 전사 · 추출 확인 필요';
    return '검토 전사 · '+(runNames[run.status] || run.status);
  }
  if(usesBaseFallback(run)) {
    if(run.status==='COMPLETED') return '기본 전사로 추출 완료 · 방언 확인 필요';
    if(needsExtractionReview(run)) return '기본 전사 완료 · 추출 확인 필요';
  }
  return needsExtractionReview(run) ? '전사 완료 · 추출 확인 필요' : (runNames[run.status] || run.status);
}
function latestRecordingRuns(runs) {
  const latest=new Map();
  for(const run of runs || []) {
    if(run.current===false) continue;
    const key=JSON.stringify([run.source_id,run.recording_id]),previous=latest.get(key);
    if(!previous || (run.seq ?? run.created_ms ?? 0)>(previous.seq ?? previous.created_ms ?? 0)) latest.set(key,run);
  }
  return [...latest.values()];
}
function pcDraftCandidate(run) {
  if(!active || run.source_id!==active.source_id || run.work_id!==active.id ||
      run.current!==true || run.evidence_current!==true || run.status!=='COMPLETED' ||
      run.evidence_sha!==active.fingerprint ||
      run.error || run.result?.error || run.extraction?.status!=='DRAFT' ||
      !latestRecordingRuns(active.pc_runs).some(latest=>latest.id===run.id)) return null;
  const source=run.result?.text_source;
  let text;
  if(source==='reviewed') text=reviewedTranscript(run);
  else if(source==='dialect' && !run.result?.dialect_error) text=run.dialect_text;
  else if(source==='base_fallback' && usesBaseFallback(run) &&
      run.result.fallback_reason===(run.result.dialect_error?'dialect_error':'dialect_empty')) text=run.asr_text;
  else if(source==='base' && (!hasTranscriptContent(run.dialect_text) || run.result?.dialect_error)) text=run.asr_text;
  else if(source==null) text=run.dialect_text ?? run.asr_text;
  if(!hasTranscriptContent(text)) return null;
  const data=run.extraction.fields, evidence=run.extraction.evidence;
  if(!data || !Array.isArray(evidence)) return null;
  const quotes=field=>evidence.filter(item=>item?.field===field && typeof item.quote==='string' &&
    item.quote.trim() && text.includes(item.quote)).map(item=>item.quote);
  const defaults=blank(), values=[];
  for(const key of fields) {
    if(['record_class','deployment_date','notes'].includes(key)) continue;
    const value=data[key], proof=quotes(key);
    if(value!=null && value!=='' && value!==defaults[key] && proof.length) values.push({key,value,quotes:proof});
  }
  const catchDefaults={species:null,quantity:null,unit:null,weight_kg:null,outcome:'UNCONFIRMED',approximate:false};
  const catches=Array.isArray(data.catches) && data.catches.length && data.catches.every((row,i)=>{
    if(!row || typeof row!=='object' || Array.isArray(row)) return false;
    const populated=Object.keys(catchDefaults).filter(key=>row[key]!=null && row[key]!=='' && row[key]!==catchDefaults[key]);
    return populated.length>0 && populated.every(key=>quotes(`catches[${i}].${key}`).length);
  }) ? data.catches : [];
  if(!values.length && !catches.length) return null;
  return {run,values,catches,quotes};
}
function currentPCCandidate(work, runId) {
  if(active!==work) return null;
  const run=(active.pc_runs || []).find(item=>item.id===runId);
  const candidate=run && pcDraftCandidate(run);
  if(!candidate) message('candidateStatus','초안이나 원자료가 갱신되었습니다. 현재 근거를 다시 확인하세요.',true);
  return candidate;
}
function revealControl(node) {
  for(let parent=node?.parentElement;parent;parent=parent.parentElement) if(parent.tagName==='DETAILS') parent.open=true;
  node?.focus();
}
function applyPCField(work, runId, key) {
  const candidate=currentPCCandidate(work,runId), item=candidate?.values.find(value=>value.key===key);
  if(!item) return;
  const input=$('f_'+key), next=String(item.value), previous=input.value;
  if(previous!==next && previous!=='' && previous!==String(blank()[key] ?? '') &&
      !confirm(`${names[key]}의 입력값 ${labelValue(key,value(input.id,key))}을(를) PC 초안의 ${labelValue(key,item.value)}(으)로 바꿀까요?`)) return;
  input.value=next;dirty=true;
  message('candidateStatus',`${names[key]}을(를) 검토 양식에 적용했습니다. 아직 저장되지 않았습니다.`);
  revealControl(input);
}
function applyPCCatches(work, runId) {
  const candidate=currentPCCandidate(work,runId);
  if(!candidate?.catches.length) return;
  captureCatches();
  if(catchRows.length && !confirm('입력 중인 어획 목록을 이 PC 초안의 목록으로 바꿀까요? 수량을 합산하지 않습니다.')) return;
  catchRows=candidate.catches.map(row=>({...row}));renderCatches();dirty=true;
  message('candidateStatus',`어획 ${catchRows.length}행을 검토 양식에 적용했습니다. 아직 저장되지 않았습니다.`);
  revealControl($('c_0_species'));
}
function openPCReview(runId) {
  $('reviewEditor').open=true;
  if(runId) {
    $('pcDraftCandidates').open=true;
    const card=[...document.querySelectorAll('.pc-candidate')].find(node=>node.dataset.runId===runId);
    if(card) {card.scrollIntoView({block:'start'});card.querySelector('button')?.focus({preventScroll:true});return;}
  }
  revealControl($('f_work_type'));
}
function renderPCCandidates() {
  const box=$('pcDraftCandidates');if(!box || !active) return;
  const candidates=latestRecordingRuns(active.pc_runs).map(pcDraftCandidate).filter(Boolean);
  const signature=JSON.stringify(candidates.map(candidate=>candidate.run));
  if(box.dataset.signature===signature) return;
  box.dataset.signature=signature;box.replaceChildren();
  box.append(el('summary',`자동으로 찾은 내용 ${candidates.length}건에서 선택`));
  if(!candidates.length) {box.append(el('p','현재 근거와 일치하고 적용할 값이 있는 PC 초안이 없습니다. 원음과 전사를 확인해 아래에 직접 입력하세요.','pc-empty'));return;}
  box.append(el('p','녹음과 맞는 값만 가져오세요. 가져온 뒤 아래에서 확인하고 저장합니다.'));
  const work=active;
  for(const candidate of candidates) {
    const {run,values,catches,quotes}=candidate;
    const recording=active.evidence.recordings.find(item=>item.id===run.recording_id);
    const card=el('article',null,'candidate pc-candidate');card.dataset.runId=run.id;
    card.append(el('strong',`${phaseNames[recording?.phase] || '이전 기록'} · ${recording?.label || '제목 없음'}`));
    const source=run.result?.text_source;
    const sourceLabel=source==='reviewed'?'검토 전사':usesBaseFallback(run)?'기본 전사 대체':source==='dialect'||source==null&&run.dialect_text!=null?'방언 전사':'기본 전사';
    card.append(el('small',`${sourceLabel} · 처리 ${fmt(run.created_ms)} · 미확정 초안`));
    if(run.extraction.uncertainties?.length) {
      const warning=el('div',null,'warning');warning.append(el('strong','확인할 내용'));
      for(const item of run.extraction.uncertainties) if(typeof item==='string') warning.append(el('p',item));
      card.append(warning);
    }
    for(const {key,value,quotes:proof} of values) {
      const row=el('div',null,'value-row'), description=el('div',null,'candidate-value');
      description.append(el('strong',`${names[key]}: ${labelValue(key,value)}`));
      for(const quote of [...new Set(proof)]) description.append(el('p','근거: '+quote,'candidate-quote'));
      const button=el('button','가져오기');button.dataset.field=key;
      button.setAttribute('aria-label',`${names[key]} ${labelValue(key,value)} 적용`);
      button.onclick=()=>applyPCField(work,run.id,key);row.append(description,button);card.append(row);
    }
    if(catches.length) {
      const group=el('section',null,'candidate-catches');group.append(el('h4',`어획 목록 ${catches.length}행`));
      catches.forEach((row,i)=>{
        group.append(fieldSummary({catches:[row]}));
        group.lastElementChild.querySelector('dt').textContent=`어획 ${i+1}`;
        group.append(el('p',`${labelValue('outcome',row.outcome)}${row.approximate?' · 근삿값':''}`,'audio-meta'));
      });
      const proof=el('details');proof.append(el('summary','어획 항목별 전사 근거'));
      catches.forEach((row,i)=>{for(const key of ['species','quantity','unit','weight_kg','outcome','approximate']) {
        for(const quote of [...new Set(quotes(`catches[${i}].${key}`))]) proof.append(el('p',`어획 ${i+1} · ${names[key]}: ${quote}`));
      }});
      const button=el('button','이 어획 목록 가져오기');button.className='apply-pc-catches';
      button.onclick=()=>applyPCCatches(work,run.id);group.append(proof,button);card.append(group);
    }
    box.append(card);
  }
}
function workRunLabel(work) {
  if(work.pc_status==='FAILED') {
    const failed=latestRecordingRuns(processing?.runs).filter(run=>run.source_id===work.source_id && run.work_id===work.id && ['FAILED','INTERRUPTED'].includes(run.status));
    if(failed.length && failed.every(needsExtractionReview)) return failed.some(isReviewedRun)?'검토 전사 · 추출 확인 필요':'전사 완료 · 추출 확인 필요';
  }
  if(work.pc_status==='COMPLETED' && latestRecordingRuns(processing?.runs).some(run=>run.source_id===work.source_id && run.work_id===work.id && isReviewedRun(run))) return '검토 전사로 추출 완료 포함';
  if(work.pc_status==='COMPLETED' && latestRecordingRuns(processing?.runs).some(run=>run.source_id===work.source_id && run.work_id===work.id && usesBaseFallback(run))) return '전사 완료 · 기본 전사 대체 포함';
  return runNames[work.pc_status] || work.pc_status;
}
function runClass(status) { return ['FAILED','INTERRUPTED'].includes(status) ? 'failed' : ['QUEUED','RUNNING'].includes(status) ? 'running' : 'draft'; }
async function refresh() {
  overview = await api('/api/overview');
  const keep = $('sourceFilter').value;
  $('sourceNames').replaceChildren();
  $('sourceFilter').replaceChildren(new Option('모든 기기', ''));
  for (const s of overview.sources) {
    $('sourceNames').append(new Option(s.id, s.id));
    $('sourceFilter').append(new Option(s.id, s.id));
  }
  $('sourceFilter').value = keep;
  message('stats', `기록 ${overview.works.length}건 · 확인 완료 ${overview.works.filter(w => w.status === '확정').length}건${overview.works.some(w=>w.status==='재검토 필요')?' · 다시 확인할 기록 '+overview.works.filter(w=>w.status==='재검토 필요').length+'건':''}`);
  renderList();
  renderImports();
}
function renderList() {
  if (!overview) return;
  const list = $('workList'), query = $('search').value.trim().normalize('NFC').toLocaleLowerCase('ko-KR');
  const source = $('sourceFilter').value, status = $('statusFilter').value, pc = $('pcFilter').value;
  const filterSummary=document.querySelector('.search-options>summary');
  if(filterSummary) filterSummary.textContent=`상세 검색${status || pc?' · 조건 적용 중':''}`;
  list.replaceChildren();
  const rows = overview.works.filter(w => {
    const pcState = !w.pc_status || w.pc_status === 'NOT_PROCESSED' ? 'NONE' : w.pc_status;
    const haystack = [w.source_id,w.id,w.session_id,w.status,fmt(w.started_ms),new Date(w.started_ms).toISOString().slice(0,10),w.search_text,w.title].filter(Boolean).join(' ').normalize('NFC').toLocaleLowerCase('ko-KR');
    return (!source || w.source_id === source) && (!status || w.status === status) &&
      (!query || query.split(/\s+/).every(part => haystack.includes(part))) &&
      (!pc || (pc === 'ACTIVE' ? ['QUEUED','RUNNING'].includes(pcState) : pc === 'FAILED' ? ['FAILED','INTERRUPTED'].includes(pcState) : pcState === pc));
  });
  $('listCount').textContent = `${rows.length}건 표시 / 전체 ${overview.works.length}건`;
  if (!rows.length) list.append(el('p', overview.works.length ? '검색 조건에 맞는 기록이 없습니다.' : '아래에서 휴대전화 ZIP을 가져오세요.', 'empty'));
  for (const w of rows) {
    const b = el('button', null, 'work');
    b.setAttribute('aria-pressed', String(active?.id === w.id && active?.source_id === w.source_id));
    b.append(el('small', w.source_id), el('strong', fmt(w.started_ms)), el('span', w.status, 'badge' + (w.status === '확정' ? ' confirmed' : '')), el('span', `녹음 ${w.recordings}건`, 'badge'));
    if (w.is_mock) b.append(el('span', '모의 자료', 'badge'));
    if (w.pc_status && !['NONE','NOT_PROCESSED'].includes(w.pc_status)) b.append(el('span', 'PC ' + workRunLabel(w), 'badge ' + (workRunLabel(w).includes('추출 확인 필요') ? 'draft' : runClass(w.pc_status))));
    const excerpt=w.title || w.search_excerpt || (w.search_text || '').replace(/\s+/g,' ').trim().slice(0,90);
    if (excerpt) b.append(el('span', excerpt, 'work-excerpt'));
    b.onclick = () => openWork(w.source_id, w.id);
    list.append(b);
  }
}
async function openWork(source, id, preserveTranscriptEdits=false) {
  if (!preserveTranscriptEdits && (dirty || hasPendingTranscriptEdits()) && !confirm('저장하지 않은 입력이 있습니다. 작업을 열까요? 전사 수정 입력은 이 창에서 유지됩니다.')) return;
  const seq = ++loadSeq;
  try {
    const w = await api('/api/work?' + new URLSearchParams({source, work:id}));
    if (seq !== loadSeq) return;
    active = w; dirty = false;
    renderList(); renderWork();
  } catch (e) { message('stats', e.message, true); }
}
function control(key, val, id, catchMode = false) {
  const label = el('label', names[key] || key);
  let input;
  if (options[key]) {
    input = el('select');
    for (const [v,t] of options[key]) { if (catchMode && v === 'NOT_FISHED') continue; input.append(new Option(t,v)); }
  } else {
    input = el('input'); input.type = numeric.has(key) ? 'number' : key === 'deployment_date' ? 'date' : 'text';
    if (numeric.has(key)) { input.min='0'; input.max='1000000000'; input.step=['workers','haul_quantity','redeploy_quantity','gear_quantity'].includes(key) ? '1' : 'any'; }
  }
  input.id = id; input.value = val ?? ''; input.oninput = () => dirty = true;
  label.append(input); return label;
}
function blank() { return Object.fromEntries(fields.map(k => [k,k==='record_class'?'UNCLASSIFIED':k==='notes_state'?'NOT_MENTIONED':k==='outcome'||k==='disposition'?'UNCONFIRMED':null])); }
function transcriptSection(title, text, cls='') {
  const box = el('section', null, 'transcript-section ' + cls);
  box.append(el('h4', title), el('p', text || '전사 원문 없음', 'transcript'));
  return box;
}
function fieldSummary(data) {
  const dl = el('dl', null, 'field-summary');
  if (!data || typeof data !== 'object') return dl;
  for (const k of fields) {
    if (data[k] == null || data[k] === '') continue;
    const row = el('div'); row.append(el('dt', names[k]), el('dd', labelValue(k,data[k]))); dl.append(row);
  }
  if (Array.isArray(data.catches)) for (const [i,c] of data.catches.entries()) {
    const row=el('div');
    if(!c || typeof c!=='object' || Array.isArray(c)) {row.append(el('dt',`어획 ${i+1}`),el('dd','형식 확인 필요'));dl.append(row);continue;}
    row.append(el('dt', `어획 ${i+1}`), el('dd', `${c.species || '어종 미확인'} · ${c.quantity == null ? '수량 미확인' : c.quantity + ' ' + (c.unit || '단위 미확인')} · ${c.weight_kg == null ? '중량 미확인' : c.weight_kg + ' kg'}`)); dl.append(row);
  }
  return dl;
}
function renderWork() {
  const w=active, p=$('workPanel'); p.replaceChildren(); pcContainers.clear();transcriptEditContainers.clear();
  const title=el('div',null,'work-title'), titleText=el('div');
  titleText.append(el('span',w.source_id,'eyebrow'),el('h3',fmt(w.started_ms)+' 작업'),el('small',`${w.evidence.recordings.length}개 녹음`));
  const write=el('button','기록 확인·저장','primary');write.onclick=()=>openPCReview();
  title.append(titleText,write); p.append(title);
  p.append(el('p','1 녹음 듣기 → 2 기록 확인 → 3 저장','work-steps'));
  if(w.is_mock) p.append(el('div','모의 자료입니다. 실제 어획 실적과 구분해서 검토하세요.','warning'));
  const process=el('details',null,'process-panel processing-options'), action=el('div',null,'actions'), processButton=el('button','음성을 글로 바꾸기','primary');
  process.open=!(w.pc_runs || []).length;
  process.append(el('summary','음성을 다시 읽으려면'));
  processButton.id='processWork'; processButton.onclick=queueWork;
  const processOptions=el('div',null,'actions');
  const dialectLabel=el('label','말투 선택'),dialectSelect=el('select');dialectSelect.id='dialectChoice';dialectSelect.onchange=()=>{dialectChoice=dialectSelect.value;dialectUserChoice=true;};dialectLabel.append(dialectSelect);
  processOptions.append(dialectLabel,processButton);action.append(processOptions);
  const processMessage=el('p');processMessage.id='processMessage';processMessage.setAttribute('role','status');processMessage.setAttribute('aria-live','polite');
  process.append(el('p','녹음을 글로 바꾸고 조업 내용을 자동으로 찾습니다.'),action,processMessage);
  p.append(el('h3','1. 녹음 듣기','step-heading'));
  for(const r of w.evidence.recordings) {
    const box=el('article',null,'audio-block'), heading=el('div',null,'audio-heading');
    heading.append(el('strong',`${phaseNames[r.phase] || '이전 기록'} · ${r.label || '제목 없음'}`));
    if(r.status!=='COMPLETED') heading.append(el('span',r.status==='INTERRUPTED'?'녹음 중단':r.status,'badge failed'));
    box.append(heading);
    const audio=el('audio');audio.controls=true;audio.preload='none';audio.src=w.audio[r.id];audio.setAttribute('aria-label',`${r.label || '작업 녹음'} 원음 듣기`);
    box.append(audio,el('div',`녹음 시각 ${fmt(r.started_ms)}`,'audio-meta'));
    const phone=w.evidence.transcriptions.filter(t=>t.recording_id===r.id), latest=phone.at(-1);
    const phoneBox=el('div');
    if(latest) {
      const block=transcriptSection('휴대전화에서 읽은 내용',latest.text || latest.error || '읽은 내용 없음');
      block.append(el('small',`${recordingNames[latest.status] || latest.status} · ${fmt(latest.created_ms)}`));
      const reviews=w.evidence.transcript_reviews.filter(x=>phone.some(t=>t.id===x.transcription_id));
      phoneBox.append(block);
      if(reviews.length) phoneBox.append(transcriptSection('휴대전화에서 확인한 문장',reviews.at(-1).text,'reviewed'));
      if(!(w.pc_runs || []).some(run=>run.recording_id===r.id)) box.append(phoneBox);
    }
    const pcBox=el('div');pcContainers.set(r.id,pcBox);box.append(pcBox);
    const transcriptEditBox=el('div');transcriptEditContainers.set(r.id,transcriptEditBox);box.append(transcriptEditBox);
    const history=el('details',null,'record-details');history.append(el('summary','휴대전화 기록·녹음 정보'));
    if(!phoneBox.parentElement) history.append(phoneBox);
    const point=w.evidence.locations.find(x=>x.id===r.start_location_id);
    history.append(el('p',point?`시작 위치 ${point.latitude}, ${point.longitude} · 실제 측정 ${fmt(point.measured_ms)}`:'시작 위치 미확보','audio-meta'),el('small','녹음 ID '+r.id));
    for(const t of phone) {
      const block=el('details');block.append(el('summary',`휴대전화 전사 ${recordingNames[t.status] || t.status} · ${fmt(t.created_ms)}`),el('p',t.text || t.error || '원문 없음','transcript'));
      for(const rev of w.evidence.transcript_reviews.filter(x=>x.transcription_id===t.id)) block.append(el('strong','휴대전화 확인본'),el('p',rev.text,'transcript'));
      history.append(block);
    }
    box.append(history);p.append(box);
  }
  p.append(process);
  const prev=w.history.at(-1);
  if(prev) {
    const box=el('section',null,'review-summary');box.append(el('h3','사람이 검토한 PC 기록'),el('span',prev.status==='CONFIRMED'?'확정':'검토 중','badge '+(prev.status==='CONFIRMED'?'confirmed':'draft')),el('small',` ${prev.reviewer} · ${fmt(prev.created_ms)}`));
    if(prev.evidence_sha!==w.fingerprint) box.append(el('div','원자료가 갱신되어 재검토가 필요합니다. 이전 PC 입력을 새 근거와 대조하세요.','warning'));
    box.append(fieldSummary(parse(prev.payload)));p.append(box);
  }
  renderEditor(p,w,prev);
  const technical=el('details',null,'record-details');technical.append(el('summary','원자료·상세 이력'));
  const events=el('details');events.append(el('summary','질문·답변과 작업 이력'),el('pre',JSON.stringify(w.evidence.events.map(e=>({...e,detail:parse(e.detail)})),null,2)));technical.append(events);
  const raw=el('details');raw.append(el('summary','전체 원자료'),el('pre',JSON.stringify(w.evidence,null,2)));technical.append(raw);p.append(technical);
  renderPCRuns(); updateProcessButton();
}
function renderPCRuns() {
  if(!active) return;
  const runs=active.pc_runs || [];
  for(const [recordingId,box] of pcContainers) {
    const own=runs.filter(r=>r.recording_id===recordingId).slice().sort((a,b)=>(b.seq ?? b.created_ms ?? 0)-(a.seq ?? a.created_ms ?? 0));
    const signature=JSON.stringify(own);
    if(box.dataset.signature===signature) continue;
    box.dataset.signature=signature;box.replaceChildren();
    if(!own.length) {box.append(el('p','아직 PC에서 읽지 않은 녹음입니다. 아래에서 음성을 글로 바꿀 수 있습니다.','pc-empty'));continue;}
    const history=el('details',null,'run-history');history.append(el('summary',`이전 처리 기록 ${own.length-1}건`));
    for(const [i,run] of own.entries()) {
      const card=el(i?'details':'div',null,'pc-run');
      if(i) card.append(el('summary',`이전 PC 처리 · ${runLabel(run)} · ${fmt(run.created_ms)}`));
      else card.append(el('span','PC '+runLabel(run),'badge '+(needsExtractionReview(run)?'draft':runClass(run.status))));
      card.append(el('small',fmt(run.created_ms)));
      if(run.current===false) card.append(el('p','이전 원음 또는 질문 연결의 처리 결과입니다. 현재 근거와 대조하세요.','warning'));
      else if(run.evidence_current===false) card.append(el('p','처리 이후 작업 근거가 갱신되었습니다. 자동 추출 항목을 다시 확인하세요.','warning'));
      const original=originalTranscripts(run), baseText=original.asr_text, dialectText=original.dialect_text;
      const originals=el('details',null,'record-details');originals.append(el('summary','다른 방식으로 읽은 원문 비교'));
      if(baseText!=null) originals.append(transcriptSection('기본 방식으로 읽은 원문',baseText,'pc'));
      if(dialectText!=null) originals.append(transcriptSection('방언을 반영해 읽은 원문',dialectText,'dialect'));
      if(isReviewedRun(run)) {
        const review=savedTranscriptReview(run);
        const section=transcriptSection('선택·수정한 내용',review?.text,'reviewed pc-reviewed primary-transcript');
        section.append(el('small',`${transcriptSelectionNames[review?.selection] || '전사 검토'} · ${review?.reviewer || '검토자 미확인'} · ${fmt(review?.reviewed_at_ms)}`));
        if(review?.reason) section.append(el('p','검토 사유: '+review.reason,'review-reason'));
        card.append(section);
      } else {
        const useDialect=run.result?.text_source==='dialect' || run.result?.text_source==null && hasTranscriptContent(dialectText) && !original.dialect_error;
        const text=useDialect?dialectText:baseText;
        if(text!=null) card.append(transcriptSection(useDialect?'읽은 내용 · 방언 반영':'읽은 내용 · 기본 방식',text,'pc primary-transcript'));
      }
      if(baseText!=null || dialectText!=null) card.append(originals);
      if(usesBaseFallback(run)) card.append(el('p',run.result.fallback_reason==='dialect_error' ? '방언 전사 처리에 실패해 PC 기본 전사로 항목을 추출했습니다. 방언 결과는 확인이 필요합니다.' : '방언 전사에 유효한 내용이 없어 PC 기본 전사로 항목을 추출했습니다. 방언 결과는 확인이 필요합니다.','warning'));
      if(run.result?.question_context) {
        const context=run.result.question_context, linked=el('section',null,'linked-question');
        linked.append(el('strong','이 답변에 연결된 질문'),el('p',context.question),el('small',`답변 반영 항목: ${names[context.target_field] || context.target_field} · 답변에 나온 수량만 사용`));
        card.append(linked);
      }
      if(run.error) {
        card.append(el('p','자동으로 정리하지 못했습니다. 녹음을 듣고 내용을 확인해 주세요.','warning'));
        const error=el('details',null,'record-details');error.append(el('summary','처리하지 못한 이유'),el('p',run.error,'error'));card.append(error);
      }
      if(['QUEUED','RUNNING'].includes(run.status)) card.append(el('p',run.status==='QUEUED'?'순서를 기다리고 있습니다.':isReviewedRun(run)?'검토 전사에서 항목을 추출하고 있습니다.':'이 컴퓨터에서 원음을 처리하고 있습니다.','pc-empty'));
      if(run.extraction) {
        const result=el('details');result.append(el('summary',run.status==='FAILED'?'자동으로 찾은 내용 · 다시 확인 필요':'자동으로 찾은 내용 · 확인 전'));
        if(run.status==='FAILED') result.append(el('p','아래 항목은 검증을 통과하지 못했습니다. 원음·전사와 대조해 직접 확인하세요.','warning'));
        result.append(el('p','녹음별 추출 결과입니다. 추가 답변과 과거 기록의 수량을 더하지 마세요.','pc-empty'));
        result.append(fieldSummary(run.extraction.fields || run.extraction));
        if(Array.isArray(run.extraction.uncertainties) && run.extraction.uncertainties.length) {
          const uncertain=el('div',null,'warning');uncertain.append(el('strong','확인할 내용'));
          for(const item of run.extraction.uncertainties) if(typeof item==='string') uncertain.append(el('p',item));
          result.append(uncertain);
        }
        if(Array.isArray(run.extraction.evidence) && run.extraction.evidence.length) {
          const evidence=el('details');evidence.append(el('summary','항목별 전사 근거'));
          for(const item of run.extraction.evidence) if(item && typeof item.field==='string' && typeof item.quote==='string') {
            const match=item.field.match(/^catches\[(\d+)\]\.(.+)$/);
            const label=match?`어획 ${Number(match[1])+1} · ${names[match[2]] || match[2]}`:(names[item.field] || item.field);
            evidence.append(el('p',`${label}: ${item.quote}`));
          }
          result.append(evidence);
        }
        const raw=el('details');raw.append(el('summary','추출 전체와 불확실성 보기'),el('pre',JSON.stringify(run.extraction,null,2)));result.append(raw);card.append(result);
      }
      if(pcDraftCandidate(run)) {
        const review=el('button','이 내용으로 기록 작성');review.className='pc-review-link primary';
        review.onclick=()=>openPCReview(run.id);card.append(review);
      } else if(!i && needsExtractionReview(run)) {
        const review=el('button','기록 직접 입력');review.onclick=()=>openPCReview();card.append(review);
      }
      const provenance=el('details');provenance.append(el('summary','사용 모델과 처리 근거'),el('pre',JSON.stringify({model:run.model_info,audio_sha256:run.audio_sha256,created_ms:run.created_ms,finished_ms:run.finished_ms,id:run.id,text_source:run.result?.text_source,transcription_status:run.result?.transcription_status,dialect_error:run.result?.dialect_error,question_context:run.result?.question_context,grounding:run.result?.grounding,transcript_review:savedTranscriptReview(run),asr_reused_from_run_id:run.result?.asr_reused_from_run_id,asr_parent_model_sha256:run.result?.asr_parent_model_sha256,failed_result:isReviewedRun(run)&&run.status==='FAILED'?run.result:undefined},null,2)));originals.append(provenance);
      if(!originals.parentElement) card.append(originals);
      (i?history:box).append(card);
    }
    if(own.length>1) box.append(history);
  }
  renderPCCandidates();
  renderTranscriptEditors();
}
function transcriptEditKey(work, recordingId) { return JSON.stringify([work.source_id,work.id,recordingId]); }
function hasPendingTranscriptEdits() { return [...transcriptEdits.values()].some(edit=>edit.changed || edit.busy); }
function transcriptInputSignature(edit) { return JSON.stringify([edit.selection,edit.text,edit.reviewer,edit.reason,edit.correctedDraft]); }
function canReviewTranscript(run) {
  return Boolean(active && run && run.source_id===active.source_id && run.work_id===active.id &&
    overview?.sources.some(source=>source.id===active.source_id && source.head===active.head) &&
    run.current===true && run.evidence_current===true && run.evidence_sha===active.fingerprint &&
    ['COMPLETED','FAILED','INTERRUPTED'].includes(run.status) &&
    latestRecordingRuns(active.pc_runs).some(latest=>latest.id===run.id));
}
function transcriptEditCurrent(edit) {
  if(!active || edit.source!==active.source_id || edit.workId!==active.id ||
      edit.head!==active.head || edit.evidenceSha!==active.fingerprint) return false;
  const run=(active.pc_runs || []).find(item=>item.id===edit.parentRunId);
  return canReviewTranscript(run);
}
function makeTranscriptEdit(run, previous=null) {
  const original=originalTranscripts(run);
  const seed=savedTranscriptReview(run)?.text ??
    (hasTranscriptContent(original.dialect_text) && !original.dialect_error?original.dialect_text:original.asr_text) ?? '';
  const edit={key:transcriptEditKey(active,run.recording_id),source:active.source_id,workId:active.id,
    recordingId:run.recording_id,parentRunId:run.id,head:active.head,evidenceSha:active.fingerprint,
    baseText:original.asr_text,dialectText:original.dialect_text,dialectError:original.dialect_error,
    selection:'',text:'',reviewer:'',reason:'',seed,correctedDraft:seed,
    changed:false,busy:false,open:previous?.open || false,status:previous?.status || '',error:false};
  edit.savedSignature=transcriptInputSignature(edit);
  return edit;
}
function refreshTranscriptEditorState(host, edit) {
  const ui=host.transcriptUI;if(!ui || host.transcriptEdit!==edit) return;
  const current=transcriptEditCurrent(edit), latest=latestRecordingRuns(active?.pc_runs).find(run=>run.recording_id===edit.recordingId);
  const running=latest && ['QUEUED','RUNNING'].includes(latest.status);
  ui.warning.hidden=current || edit.busy;
  ui.warning.textContent=running?'이 녹음의 처리가 진행 중입니다. 작성 중인 입력은 유지됩니다.':
    '원자료나 최신 처리 결과가 바뀌었습니다. 작성 중인 입력은 보존했으며, 최신 근거를 확인한 뒤 다시 검토해야 합니다.';
  ui.rebase.hidden=current || edit.busy || !canReviewTranscript(latest);
  ui.reopen.hidden=current || edit.busy || Boolean(running) || canReviewTranscript(latest);
  ui.selection.disabled=!current;
  ui.text.readOnly=edit.selection!=='corrected';
  ui.text.disabled=!edit.selection;
  ui.submit.disabled=edit.busy || !current || processingBusy || processing?.models?.ready===false ||
    !edit.selection || !hasTranscriptContent(edit.text) || !edit.reviewer.trim() || !edit.reason.trim();
  ui.submit.textContent=edit.busy?'요청 중…':'이 문장으로 다시 정리';
  ui.cancel.disabled=edit.busy;
  ui.status.textContent=edit.status || (processing?.models?.ready===false?'항목 추출 환경을 확인하세요: '+(processing.models.error || ''):'');
  ui.status.classList.toggle('error',edit.error || processing?.models?.ready===false);
}
function renderTranscriptEditors() {
  if(!active) return;
  for(const [recordingId,host] of transcriptEditContainers) {
    const key=transcriptEditKey(active,recordingId), latest=latestRecordingRuns(active.pc_runs).find(run=>run.recording_id===recordingId);
    let edit=transcriptEdits.get(key);
    if(canReviewTranscript(latest) && (!edit || !edit.changed && !edit.busy &&
        (edit.parentRunId!==latest.id || edit.head!==active.head || edit.evidenceSha!==active.fingerprint))) {
      edit=makeTranscriptEdit(latest,edit);transcriptEdits.set(key,edit);
    }
    if(!edit) {host.replaceChildren();continue;}
    if(host.transcriptEdit===edit) {refreshTranscriptEditorState(host,edit);continue;}
    host.replaceChildren();host.transcriptEdit=edit;
    const details=el('details',null,'transcript-review-editor');details.open=edit.open;
    details.dataset.recordingId=recordingId;details.dataset.parentRunId=edit.parentRunId;
    details.ontoggle=()=>{edit.open=details.open;};
    details.append(el('summary','읽은 내용이 틀렸나요?'),el('p','녹음을 듣고 맞는 문장을 선택하거나 고치세요. 이 문장에서 조업 내용을 다시 찾습니다. 기록 확정은 다음 단계에서 합니다.'));
    const warning=el('p',null,'warning');warning.setAttribute('role','status');details.append(warning);
    const rebase=el('button','입력 유지하고 최신 전사와 비교');rebase.type='button';
    rebase.onclick=()=>{
      const current=latestRecordingRuns(active?.pc_runs).find(run=>run.recording_id===recordingId);
      if(!canReviewTranscript(current) || key!==transcriptEditKey(active,recordingId) || transcriptEdits.get(key)!==edit || edit.busy) return;
      const next=makeTranscriptEdit(current,edit);
      next.selection=hasTranscriptContent(edit.text)?'corrected':'';next.text=next.selection?edit.text:'';
      next.correctedDraft=edit.text || edit.correctedDraft;next.reviewer=edit.reviewer;next.reason=edit.reason;
      next.changed=transcriptInputSignature(next)!==next.savedSignature;next.open=true;
      next.status='기존 입력을 수정 문장으로 유지했습니다. 최신 원음·전사와 비교한 뒤 다시 추출하세요.';
      transcriptEdits.set(key,next);renderTranscriptEditors();
    };
    const reopen=el('button','작업을 다시 열어 최신 근거 확인');reopen.type='button';
    reopen.onclick=()=>openWork(edit.source,edit.workId);
    const staleActions=el('div',null,'actions');staleActions.append(rebase,reopen);details.append(staleActions);
    const form=el('form'), selectLabel=el('label','사용할 문장'), selection=el('select');
    selection.className='transcript-review-selection';selection.required=true;
    selection.append(new Option('문장을 선택하세요',''));
    if(hasTranscriptContent(edit.baseText)) selection.append(new Option('기본 방식으로 읽은 문장','base'));
    if(hasTranscriptContent(edit.dialectText) && !edit.dialectError) selection.append(new Option('방언을 반영한 문장','dialect'));
    selection.append(new Option('문장 직접 수정','corrected'));selection.value=edit.selection;selectLabel.append(selection);
    const textLabel=el('label','녹음 내용'), text=el('textarea');text.className='transcript-review-text';
    text.value=edit.text;text.required=true;text.maxLength=100000;text.rows=5;
    text.placeholder='위에서 문장을 선택하세요.';textLabel.append(text);
    const reviewerLabel=el('label','확인한 사람'), reviewer=el('input');reviewer.className='transcript-review-reviewer';
    reviewer.value=edit.reviewer;reviewer.required=true;reviewer.maxLength=120;reviewer.autocomplete='off';reviewerLabel.append(reviewer);
    const reasonLabel=el('label','선택하거나 고친 이유'), reason=el('textarea');reason.className='transcript-review-reason';
    reason.value=edit.reason;reason.required=true;reason.maxLength=2000;reason.rows=2;
    reason.placeholder='예: 녹음을 다시 듣고 어종 이름을 고쳤습니다.';reasonLabel.append(reason);
    const touch=()=>{edit.changed=transcriptInputSignature(edit)!==edit.savedSignature;edit.error=false;edit.status=edit.busy?'재추출 요청 중입니다. 지금 추가한 입력은 요청이 끝난 뒤에도 유지됩니다.':'작성 중입니다. 아직 재추출을 요청하지 않았습니다.';refreshTranscriptEditorState(host,edit);};
    selection.onchange=()=>{
      if(edit.selection==='corrected') edit.correctedDraft=text.value;
      edit.selection=selection.value;edit.text=edit.selection==='base'?edit.baseText:edit.selection==='dialect'?edit.dialectText:edit.selection==='corrected'?edit.correctedDraft:'';
      text.value=edit.text;touch();
    };
    text.oninput=()=>{edit.text=text.value;if(edit.selection==='corrected') edit.correctedDraft=edit.text;touch();};
    reviewer.oninput=()=>{edit.reviewer=reviewer.value;touch();};reason.oninput=()=>{edit.reason=reason.value;touch();};
    const actions=el('div',null,'actions'), submit=el('button','이 문장으로 다시 정리','primary');submit.type='submit';submit.classList.add('transcript-review-submit');
    const cancel=el('button','작성 취소');cancel.type='button';cancel.className='transcript-review-cancel';
    cancel.onclick=()=>{
      if(edit.busy || transcriptEdits.get(key)!==edit) return;
      if(edit.changed && !confirm('작성 중인 전사 검토 입력을 지울까요? 저장된 원문과 이전 이력은 유지됩니다.')) return;
      transcriptEdits.delete(key);host.transcriptEdit=null;renderTranscriptEditors();
    };
    actions.append(submit,cancel);
    const status=el('p',null,'transcript-review-status');status.setAttribute('role','status');status.setAttribute('aria-live','polite');
    form.append(selectLabel,textLabel,reviewerLabel,reasonLabel,actions,status);form.onsubmit=event=>{event.preventDefault();submitTranscriptReview(edit);};
    details.append(form);host.append(details);
    host.transcriptUI={warning,rebase,reopen,selection,text,reviewer,reason,submit,cancel,status};
    refreshTranscriptEditorState(host,edit);
  }
}
async function submitTranscriptReview(edit) {
  if(edit.busy || transcriptEdits.get(edit.key)!==edit) return;
  if(!transcriptEditCurrent(edit)) {edit.status='최신 원자료·처리 결과와 일치하지 않습니다. 입력을 보존했으니 최신 근거를 확인하세요.';edit.error=true;renderTranscriptEditors();return;}
  if(!Object.hasOwn(transcriptSelectionNames,edit.selection) || !hasTranscriptContent(edit.text) || !edit.reviewer.trim() || !edit.reason.trim()) {
    edit.status='전사 문장·검토자·선택 또는 수정 사유를 모두 입력하세요.';edit.error=true;renderTranscriptEditors();return;
  }
  if(edit.selection==='base' && edit.text!==edit.baseText || edit.selection==='dialect' && (edit.text!==edit.dialectText || edit.dialectError)) {
    edit.status='선택한 원래 전사와 문장이 다릅니다. 문장을 바꾸려면 직접 수정을 선택하세요.';edit.error=true;renderTranscriptEditors();return;
  }
  if(new TextEncoder().encode(edit.text).length>100000) {edit.status='전사 문장이 너무 깁니다. 100,000바이트 이내로 입력하세요.';edit.error=true;renderTranscriptEditors();return;}
  const signature=transcriptInputSignature(edit), payload={source:edit.source,work_id:edit.workId,parent_run_id:edit.parentRunId,
    head:edit.head,evidence_sha:edit.evidenceSha,selection:edit.selection,text:edit.text,reviewer:edit.reviewer,reason:edit.reason};
  edit.busy=true;edit.status='검토 문장과 사유를 보존하고 항목 재추출을 요청하고 있습니다.';edit.error=false;
  renderTranscriptEditors();updateProcessButton();
  try {
    const result=await api('/api/reextract',payload), count=(result.jobs?.length || 0)+(result.skipped?.length || 0);
    if(!count) throw Error('재추출 요청 결과를 확인할 수 없습니다. 처리 상태를 확인하세요.');
    if(transcriptEdits.get(edit.key)===edit && transcriptInputSignature(edit)===signature) {
      edit.selection='';edit.text='';edit.reviewer='';edit.reason='';edit.correctedDraft=edit.seed;
      edit.savedSignature=transcriptInputSignature(edit);edit.changed=false;
      const host=transcriptEditContainers.get(edit.recordingId);
      if(host?.transcriptEdit===edit) host.transcriptEdit=null;
    }
    edit.status=`항목 재추출 대기 ${result.jobs?.length || 0}건 · 이미 요청된 처리 ${result.skipped?.length || 0}건. 결과는 미확정 초안입니다.${edit.changed?' 요청 후 추가한 입력은 그대로 유지했습니다.':''}`;
    processingSignature='';await pollProcessing();
  } catch(error) {edit.status='재추출 요청 실패: '+error.message;edit.error=true;}
  finally {edit.busy=false;renderTranscriptEditors();updateProcessButton();}
}
function renderEditor(parent,w,prev) {
  const p=el('details',null,'review-editor');p.id='reviewEditor';p.append(el('summary','2. 기록 확인'),el('p','자동으로 찾은 값을 가져오거나 직접 입력하세요. 모르는 값은 비워 두세요.'));parent.append(p);
  const pcCandidates=el('details');pcCandidates.id='pcDraftCandidates';p.append(pcCandidates);
  const candidates=el('details');candidates.append(el('summary',`휴대전화에서 찾은 내용 ${w.candidates.length}건`));
  for(const c of w.candidates) {
    const card=el('div',null,'candidate');card.append(el('strong',c.kind),el('small',' · '+c.id));
    for(const key of fields) {
      const val=c.fields[key];if(val==null||val===''||val==='UNCONFIRMED') continue;
      const row=el('div',null,'value-row');row.append(el('span',`${names[key]}: ${labelValue(key,val)}`));
      const b=el('button','가져오기');b.onclick=()=>{$('f_'+key).value=val;dirty=true;revealControl($('f_'+key));};row.append(b);card.append(row);
    }
    if(c.fields.deployment_date_candidate) card.append(el('div','투망일 후보 '+c.fields.deployment_date_candidate+' — 원음을 확인하고 날짜에 직접 입력하세요.','warning'));
    if(c.fields.catches?.length) {
      const b=el('button','이 후보의 어획 목록 적용');b.onclick=()=>{if(catchRows.length&&!confirm('입력 중인 어획 목록을 이 후보로 바꿀까요? 원자료는 그대로 보존됩니다.'))return;catchRows=c.fields.catches.map(x=>({...x}));renderCatches();dirty=true;};card.append(el('pre',JSON.stringify(c.fields.catches,null,2)),b);
    }
    const raw=el('details');raw.append(el('summary','후보 전체와 불확실성 보기'),el('pre',JSON.stringify(c.fields,null,2)));card.append(raw);candidates.append(card);
  }
  if(w.candidates.length) p.append(candidates);
  const candidateStatus=el('p',null,'candidate-status');candidateStatus.id='candidateStatus';candidateStatus.setAttribute('role','status');candidateStatus.setAttribute('aria-live','polite');p.append(candidateStatus);
  const values=prev?parse(prev.payload):blank();catchRows=prev?values.catches.map(x=>({...x})):[];
  const group=(title,keys,collapsed=false)=>{
    const section=el(collapsed?'details':'section',null,collapsed?'advanced-fields':'form-section');
    section.append(el(collapsed?'summary':'h4',title));
    if(collapsed) section.open=keys.some(key=>values[key]!=null && values[key]!==blank()[key] && values[key]!=='');
    const grid=el('div',null,'form-grid');for(const key of keys) grid.append(control(key,values[key],'f_'+key));section.append(grid);p.append(section);
  };
  group('작업 정보',['record_class','work_type','outcome']);
  group('어구·투망·양망 정보',['gear_type','gear_unit','deployment_date','haul_quantity','redeploy_quantity','disposition'],true);
  p.append(el('h4','잡은 어종과 양'));
  const catches=el('div');catches.id='catches';p.append(catches);
  const add=el('button','어종 추가');add.onclick=()=>{captureCatches();catchRows.push({species:null,quantity:null,unit:null,weight_kg:null,outcome:'UNCONFIRMED',approximate:false});renderCatches();dirty=true;};p.append(add);renderCatches();
  group('특이사항',['notes_state','notes_text']);
  group('추가 정보·메모',['target_species','gear_quantity','workers','notes'],true);
  p.append(el('h3','3. 저장','step-heading'),el('p','계속 확인할 기록은 임시 저장하고, 확인을 마쳤으면 확정하세요.'));
  const reviewer=control('reviewer',prev?.reviewer||'','reviewer');reviewer.firstChild.textContent='확인한 사람';
  const reason=el('label','확인 내용'),why=el('textarea');why.id='reason';why.placeholder='확인한 내용이나 모르는 값을 비워 둔 이유';why.oninput=()=>dirty=true;reason.append(why);p.append(reviewer,reason);
  const ack=el('label',null,'check'),check=el('input');check.type='checkbox';check.id='missingAck';check.onchange=()=>dirty=true;ack.append(check,el('span','필수 항목이 미확인이어도 빈 값과 사유를 보존하여 확정합니다.'));p.append(ack);
  const buttons=el('div',null,'actions');for(const [status,label] of [['DRAFT','임시 저장'],['CONFIRMED','확인 완료·확정']]) {const b=el('button',label,status==='CONFIRMED'?'primary':'');b.onclick=()=>save(status,b);buttons.append(b);}
  const msg=el('p',null,'save-status');msg.id='saveStatus';msg.setAttribute('role','status');p.append(buttons,msg);
  const history=el('details');history.append(el('summary',`PC 확인·수정 이력 ${w.history.length}건`));
  for(const h of w.history.slice().reverse()) {const div=el('div',null,'history');div.append(el('strong',`${h.status} · ${h.reviewer} · ${fmt(h.created_ms)}`),el('p',h.reason),el('pre',JSON.stringify({values:parse(h.payload),missing:parse(h.missing),source:h.snapshot_sha,previous:h.previous_id},null,2)));history.append(div);}
  p.append(history);
}
function value(id,key) {const v=$(id).value;return v===''?null:numeric.has(key)?Number(v):v;}
function captureCatches() {catchRows=catchRows.map((r,i)=>Object.fromEntries(['species','quantity','unit','weight_kg','outcome'].map(k=>[k,value(`c_${i}_${k}`,k)]).concat([['approximate',$(`c_${i}_approximate`).checked]])));}
function renderCatches() {
  const box=$('catches');box.replaceChildren();
  catchRows.forEach((r,i)=>{
    const card=el('div',null,'catch'),grid=el('div',null,'form-grid');card.append(el('strong',`어획 ${i+1}`));
    for(const k of ['species','quantity','unit','weight_kg','outcome']) grid.append(control(k,r[k],`c_${i}_${k}`,true));
    const label=el('label',null,'check'),check=el('input');check.type='checkbox';check.id=`c_${i}_approximate`;check.checked=!!r.approximate;check.onchange=()=>dirty=true;label.append(check,el('span','근삿값'));grid.append(label);
    const remove=el('button','이 어종 삭제');remove.onclick=()=>{captureCatches();catchRows.splice(i,1);renderCatches();dirty=true;};card.append(grid,remove);box.append(card);
  });
}
async function save(status,b) {
  captureCatches();const payload=Object.fromEntries(fields.map(k=>[k,value('f_'+k,k)]));payload.catches=catchRows;
  const w=active;b.disabled=true;
  try {
    const j=await api('/api/decision',{source:w.source_id,work_id:w.id,head:w.head,evidence_sha:w.fingerprint,previous_id:w.history.at(-1)?.id||null,data:payload,status,reviewer:$('reviewer').value,reason:$('reason').value,allow_missing:$('missingAck').checked});
    if(active===w) dirty=false;
    await refresh();
    if(active===w) {
      await openWork(w.source_id,w.id,true);$('reviewEditor').open=true;
      message('saveStatus',`${status==='CONFIRMED'?'확정':'검토 중'} 저장 완료. 미확인 ${j.missing.length}항목. 원자료와 이전 확인본은 보존했습니다.`);
    }
  } catch(e) {message('saveStatus',e.message,true);} finally {b.disabled=false;}
}
function renderImports() {
  const box=$('imports');box.replaceChildren();
  for(const h of overview.imports) {const div=el('details');div.append(el('summary',`${h.source_id} · 수신 ${h.receipt_no} · ${h.filename}`),el('small',fmt(h.received_ms)+' · '+h.sha),el('pre',JSON.stringify(parse(h.report),null,2)));box.append(div);}
}
function renderDialectOptions() {
  const select=$('dialectChoice');if(!select) return;
  const available=processing?.models?.available_dialects || {};
  const signature=JSON.stringify({available,selected:processing?.models?.selected_dialect});if(select.dataset.signature===signature) return;
  if(!dialectUserChoice) dialectChoice=processing?.models?.selected_dialect || 'base';
  select.dataset.signature=signature;select.replaceChildren(new Option('기본 방식','base'));
  for(const [key,model] of Object.entries(available)) select.append(new Option(model.name || ({gyeongsang:'경상 방언',jeolla:'전라 방언',jeju:'제주 방언'}[key] || key),key));
  select.value=Object.hasOwn(available,dialectChoice)?dialectChoice:'base';dialectChoice=select.value;
}
function updateProcessButton() {
  if(!$('processWork') || !active) return;
  renderDialectOptions();
  const busy=(active.pc_runs||[]).some(r=>['QUEUED','RUNNING'].includes(r.status));
  const reviewing=[...transcriptEdits.values()].some(edit=>edit.source===active.source_id && edit.workId===active.id && edit.busy);
  $('processWork').disabled=busy || reviewing || processingBusy || processing?.models?.ready===false;
  $('processWork').textContent=busy?'음성을 읽는 중…':'음성을 글로 바꾸기';
  $('dialectChoice').disabled=busy || reviewing || processingBusy;
  if(processing?.models?.ready===false) message('processMessage',processing.models.error || 'PC 전사 모델을 사용할 수 없습니다.',true);
  else if(busy) message('processMessage','대기·처리 상태는 자동으로 갱신됩니다. 다른 기록을 조회할 수 있습니다.');
  else message('processMessage','같은 설정으로 읽은 결과가 있으면 그대로 사용합니다.');
}
async function queueWork() {
  if(!active || processingBusy || [...transcriptEdits.values()].some(edit=>edit.source===active.source_id && edit.workId===active.id && edit.busy)) return;
  const w=active;processingBusy=true;updateProcessButton();
  try {
    const result=await api('/api/process',{source:w.source_id,work_id:w.id,dialect:$('dialectChoice').value});
    if(active===w) message('processMessage',`처리 대기 ${result.jobs?.length || 0}건 · 이미 처리되었거나 대기 중 ${result.skipped?.length || 0}건`);
    processingSignature='';await pollProcessing();
  } catch(e) {if(active===w) message('processMessage','PC 전사 시작 실패: '+e.message,true);}
  finally {processingBusy=false;if($('processWork')) {const busy=(active?.pc_runs||[]).some(r=>['QUEUED','RUNNING'].includes(r.status));$('processWork').disabled=busy || processing?.models?.ready===false;$('dialectChoice').disabled=busy;}}
}
async function pollProcessing() {
  clearTimeout(processingTimer);
  try {
    const state=await api('/api/processing');processing=state;
    const latest=latestRecordingRuns(state.runs), counts={}, review=latest.filter(needsExtractionReview).length, fallback=latest.filter(usesBaseFallback).length, reviewed=latest.filter(isReviewedRun).length;
    for(const run of latest) counts[run.status]=(counts[run.status] || 0)+1;
    const pending=(counts.QUEUED || 0)+(counts.RUNNING || 0), past=(state.runs || []).length-latest.length;
    const attention=(counts.FAILED || 0)+(counts.INTERRUPTED || 0);
    message('processingStatus',state.models?.ready===false?'자동 정리 준비를 확인해 주세요.':`자동 정리 완료 ${counts.COMPLETED || 0}건${attention?' · 확인 필요 '+attention+'건':''}${pending?' · 처리 중 '+pending+'건':''} · 확정 전 검토가 필요합니다.`,state.models?.ready===false);
    $('modelNames').textContent=[state.models?.error,state.models?.asr?.name, state.models?.dialect?.name ? '방언: '+state.models.dialect.name : Object.keys(state.models?.available_dialects || {}).length ? '방언 선택 가능' : '방언 모델 미설정',`항목 추출 확인 ${review}건 · 기본 전사 대체 ${fallback}건 · 검토 전사 ${reviewed}건 · 이전 처리 ${past}건`].filter(Boolean).join(' · ');
    const signature=JSON.stringify((state.runs || []).map(r=>[r.id,r.status,r.finished_ms,r.error,r.current,r.evidence_current,r.work_id]));
    if(signature!==processingSignature) {
      if(active) {
        active.pc_runs=(state.runs || []).filter(run=>run.source_id===active.source_id && run.work_id===active.id);
        renderPCRuns();
      }
    }
    await refresh();
    processingSignature=signature;
    updateProcessButton();
    renderTranscriptEditors();
  } catch(e) {message('processingStatus','PC 처리 상태를 확인할 수 없습니다: '+e.message,true);}
  finally {processingTimer=setTimeout(pollProcessing,processing?.running?2000:10000);}
}
$('preview').onclick=async()=>{const f=$('zip').files[0];if(!f)return message('importStatus','ZIP을 선택하세요.',true);preview=null;$('previewPanel').hidden=true;$('preview').disabled=true;message('importStatus','모든 표·CSV·원음·관계를 검증하고 있습니다…');try{const r=await fetch('/api/preview',{method:'POST',headers:{'X-Local-Import':'1','X-Source':encodeURIComponent($('source').value.trim()),'X-Filename':encodeURIComponent(f.name)},body:f});const j=await r.json();if(!r.ok)throw Error(j.error);preview=j;$('previewPanel').hidden=false;$('previewSummary').textContent=`${Object.keys(j.counts).length}개 표 · ${Object.values(j.counts).reduce((a,b)=>a+b,0)}행 · 원음 ${j.audio_count}개 · ${j.duplicate?'이미 가져온 현재 판본':'검증 통과'}`;$('previewDetails').textContent=JSON.stringify(j,null,2);$('receivedAfter').checked=false;$('allowChanges').checked=false;$('importReason').value='';message('importStatus','원자료 검증 완료. 아래 대조 결과와 수신 순서를 확인하세요.');}catch(e){message('importStatus','가져오기 중단: '+e.message,true);}finally{$('preview').disabled=false;}};
$('commitImport').onclick=async()=>{if(!preview)return;$('commitImport').disabled=true;try{const j=await api('/api/import',{token:preview.token,previous_sha:preview.previous_sha,received_after:$('receivedAfter').checked,allow_changes:$('allowChanges').checked,reason:$('importReason').value});message('importStatus',j.duplicate?'동일 ZIP 재확인 완료. 자료와 이력은 중복 등록하지 않았습니다.':`수신 ${j.receipt_no} 저장 완료 · 전체 행과 원음 보존 확인`);preview=null;$('previewPanel').hidden=true;await refresh();}catch(e){message('importStatus',e.message,true);}finally{$('commitImport').disabled=false;}};
$('audit').onclick=async()=>{$('audit').disabled=true;message('auditStatus','보관 자료 대조 중…');try{const j=await api('/api/audit',{});message('auditStatus',`${j.snapshots.length}개 보관 판본의 모든 값·행 순서·원음 일치`);}catch(e){message('auditStatus',e.message,true);}finally{$('audit').disabled=false;}};
$('sourceFilter').onchange=renderList;
$('statusFilter').onchange=renderList;
$('pcFilter').onchange=renderList;
$('search').oninput=renderList;
$('refresh').onclick=async()=>{try{await refresh();await pollProcessing();}catch(e){message('stats',e.message,true);}};
document.querySelector('a[href="#importPanel"]').onclick=()=>{$('importPanel').open=true;};
window.addEventListener('beforeunload',e=>{if(dirty || hasPendingTranscriptEdits()){e.preventDefault();e.returnValue='';}});
refresh().catch(e=>message('stats',e.message,true));
pollProcessing();
