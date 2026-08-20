#!/usr/bin/env node
'use strict';
/**
 * Team Room 是 invite-only。@manager 是 guest，不在房间里就 join/send 403。
 * Controller PUT Team 还会按工人名单对账并把 Manager 踢成 leave。
 * 本脚本用 Human admin 邀请 Manager，再用 Manager token join。
 */
const { execFileSync } = require('child_process');

function selfTest() {
  const already = { errcode: 'M_FORBIDDEN', error: 'already in the room' };
  if (!isAlreadyMember(200, {}) || !isAlreadyMember(403, already)) {
    throw new Error('already-member detection failed');
  }
  if (isAlreadyMember(403, { errcode: 'M_FORBIDDEN', error: 'cannot join a room that is not `public`' })) {
    throw new Error('unrelated 403 treated as membership');
  }
  process.stdout.write('[PASS] invite already-member detection\n');
}

function isAlreadyMember(status, body) {
  if (status === 200 || status === 201) return true;
  const error = String(body?.error ?? '');
  return status === 403 && /already|already in the room|is already/i.test(error);
}

function dockerExec(name, command, opts = {}) {
  const args = ['exec'];
  for (const [key, value] of Object.entries(opts.env ?? {})) {
    args.push('-e', `${key}=${value}`);
  }
  if (opts.input != null) args.push('-i');
  args.push(name, 'sh', '-lc', command);
  return execFileSync('docker', args, {
    encoding: 'utf8',
    stdio: ['pipe', 'pipe', 'pipe'],
    input: opts.input,
  }).trim();
}

function shellQuote(value) {
  return "'" + String(value).replace(/'/g, "'\\''") + "'";
}

function main() {
  const room = dockerExec('chatflows-agentteams-agent-manager-1', 'printf %s "$AGENTTEAMS_LEADER_ROOM_ID"');
  const user = dockerExec('chatflows-agentteams-agent-manager-1', 'printf %s "$AGENTTEAMS_MATRIX_USER_ID"');
  if (!room.startsWith('!') || !user.startsWith('@')) {
    throw new Error('manager container missing AGENTTEAMS_LEADER_ROOM_ID / AGENTTEAMS_MATRIX_USER_ID');
  }
  const joined = dockerExec(
    'chatflows-agentteams-agent-manager-1',
    'curl -sS -H "Authorization: Bearer $AGENTTEAMS_MATRIX_ACCESS_TOKEN" "$AGENTTEAMS_MATRIX_URL/_matrix/client/v3/joined_rooms"',
  );
  const rooms = JSON.parse(joined).joined_rooms || [];
  if (rooms.includes(room)) {
    process.stdout.write('[PASS] Manager already in Team Room\n');
    return;
  }
  const invitePy = [
    'import json,os,urllib.parse,urllib.request,urllib.error',
    'matrix=os.environ.get("AGENTTEAMS_MATRIX_URL") or "http://127.0.0.1:6167"',
    'admin=os.environ["AGENTTEAMS_ADMIN_USER"]',
    'password=os.environ["AGENTTEAMS_ADMIN_PASSWORD"]',
    'room=' + JSON.stringify(room),
    'user=' + JSON.stringify(user),
    'def req(method,path,token=None,body=None):',
    '  data=None if body is None else json.dumps(body).encode()',
    '  headers={"Content-Type":"application/json"}',
    '  if token: headers["Authorization"]="Bearer "+token',
    '  request=urllib.request.Request(matrix.rstrip("/")+path,data=data,headers=headers,method=method)',
    '  try:',
    '    with urllib.request.urlopen(request,timeout=15) as response:',
    '      raw=response.read().decode()',
    '      return response.status, json.loads(raw) if raw else {}',
    '  except urllib.error.HTTPError as error:',
    '    raw=error.read().decode()',
    '    try: parsed=json.loads(raw)',
    '    except Exception: parsed={"raw":raw[:200]}',
    '    return error.code, parsed',
    'status,login=req("POST","/_matrix/client/v3/login",None,{"type":"m.login.password","identifier":{"type":"m.id.user","user":admin},"password":password})',
    'if status//100!=2 or not login.get("access_token"):',
    '  raise SystemExit("admin login failed HTTP %s" % status)',
    'enc_room=urllib.parse.quote(room,safe="")',
    'status,body=req("POST","/_matrix/client/v3/rooms/%s/invite"%enc_room,login["access_token"],{"user_id":user})',
    'print(json.dumps({"status":status,"errcode":body.get("errcode"),"error":(body.get("error") or "")[:120]}))',
  ].join('\n');
  const invite = dockerExec('agentteams-controller', 'python3 -', { input: invitePy + '\n' });
  const parsed = JSON.parse(invite);
  if (!isAlreadyMember(parsed.status, parsed)) {
    throw new Error('invite failed: HTTP ' + parsed.status + ' ' + (parsed.errcode || parsed.error || ''));
  }
  const join = dockerExec(
    'chatflows-agentteams-agent-manager-1',
    'ENC=$(printf %s "$AGENTTEAMS_LEADER_ROOM_ID" | sed "s/:/%3A/g; s/!/%21/g"); curl -sS -o /tmp/join.json -w "%{http_code}" -X POST -H "Authorization: Bearer $AGENTTEAMS_MATRIX_ACCESS_TOKEN" -H "Content-Type: application/json" -d "{}" "$AGENTTEAMS_MATRIX_URL/_matrix/client/v3/join/$ENC"',
  );
  if (join !== '200' && join !== '201') {
    throw new Error('manager join failed: HTTP ' + join);
  }
  process.stdout.write('[PASS] Manager is in Team Room (invite+join); values not printed\n');
}

if (process.argv.includes('--self-test')) selfTest();
else main();
