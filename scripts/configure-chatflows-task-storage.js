#!/usr/bin/env node
'use strict';

const crypto=require('crypto');
const fs=require('fs');
const path=require('path');
const {spawnSync}=require('child_process');

const EXPECTED_ACCESS_KEY='chatflows-task-manager';
const EXPECTED_POLICY='chatflows-task-manager-fixed-team';
const EXPECTED_BUCKET='agentteams-storage';
const EXPECTED_PREFIX='teams/chatflows-build-team/shared/tasks';
const modes=new Set(['--plan','--apply','--check','--rollback']);
const args=process.argv.slice(2);
const mode=args.find(value=>modes.has(value))||'--plan';
const envFile=path.resolve(args.find(value=>!modes.has(value))||path.join(__dirname,'../deploy/agentteams/integration.env'));
if(args.filter(value=>modes.has(value)).length>1)throw new Error('choose exactly one of --plan, --apply, --check, --rollback');

function parseEnv(file){
 if(!fs.existsSync(file))throw new Error('env file not found: '+file);
 return Object.fromEntries(fs.readFileSync(file,'utf8').split(/\r?\n/).map(line=>line.trim()).filter(line=>line&&!line.startsWith('#')&&line.includes('=')).map(line=>{const at=line.indexOf('=');return[line.slice(0,at).trim(),line.slice(at+1).trim().replace(/^(['"])(.*)\1$/,'$2')];}));
}
function required(env,key){const value=env[key]?.trim();if(!value)throw new Error(key+' required');return value;}
function validateLine(name,value){if(/[\r\n]/.test(value))throw new Error(name+' must not contain a newline');return value;}
function saveSecret(file,secret){
 const original=fs.readFileSync(file,'utf8');
 const line='CHATFLOWS_TASK_FS_SECRET_KEY='+secret;
 const updated=/^CHATFLOWS_TASK_FS_SECRET_KEY=.*$/m.test(original)?original.replace(/^CHATFLOWS_TASK_FS_SECRET_KEY=.*$/m,line):original.replace(/\s*$/,'\n'+line+'\n');
 fs.writeFileSync(file,updated,{mode:0o600});fs.chmodSync(file,0o600);
}
function runController(input){
 const result=spawnSync('docker',['exec','-i','agentteams-controller','sh','-s'],{input,encoding:'utf8',stdio:['pipe','pipe','pipe']});
 if(result.status!==0)throw new Error('MinIO IAM operation failed (details redacted; exit '+result.status+')');
}
function policy(bucket,prefix){return JSON.stringify({Version:'2012-10-17',Statement:[
 {Effect:'Allow',Action:['s3:GetBucketLocation'],Resource:['arn:aws:s3:::'+bucket]},
 {Effect:'Allow',Action:['s3:ListBucket'],Resource:['arn:aws:s3:::'+bucket],Condition:{StringLike:{'s3:prefix':[prefix,prefix+'/*']}}},
 {Effect:'Allow',Action:['s3:GetObject','s3:PutObject','s3:DeleteObject'],Resource:['arn:aws:s3:::'+bucket+'/'+prefix+'/*']},
]});}
function shellVariables(env,secret,policyJson=''){
 const values={endpoint:validateLine('endpoint',required(env,'CHATFLOWS_TASK_FS_ENDPOINT')),access:EXPECTED_ACCESS_KEY,secret:validateLine('secret',secret),bucket:EXPECTED_BUCKET,prefix:EXPECTED_PREFIX,policy_name:EXPECTED_POLICY,policy_json:policyJson};
 return Object.entries(values).map(([key,value])=>key+'="$(printf %s '+Buffer.from(value).toString('base64')+' | base64 -d)"').join('\n')+'\n';
}

const env=parseEnv(envFile);
const endpoint=required(env,'CHATFLOWS_TASK_FS_ENDPOINT');
if(required(env,'CHATFLOWS_TASK_FS_ACCESS_KEY')!==EXPECTED_ACCESS_KEY)throw new Error('CHATFLOWS_TASK_FS_ACCESS_KEY must be '+EXPECTED_ACCESS_KEY);
if(required(env,'CHATFLOWS_TASK_FS_BUCKET')!==EXPECTED_BUCKET)throw new Error('CHATFLOWS_TASK_FS_BUCKET must be '+EXPECTED_BUCKET);
if(required(env,'CHATFLOWS_TASK_FS_PREFIX')!==EXPECTED_PREFIX)throw new Error('CHATFLOWS_TASK_FS_PREFIX must be the fixed Team shared/tasks prefix');
new URL(endpoint);

if(mode==='--plan'){
 process.stdout.write('[PLAN] create/update dedicated MinIO user '+EXPECTED_ACCESS_KEY+' and policy '+EXPECTED_POLICY+'\n');
 process.stdout.write('[PLAN] allow only bucket location, prefix-scoped listing, and object read/write/delete under '+EXPECTED_BUCKET+'/'+EXPECTED_PREFIX+'/*\n');
 process.stdout.write('[PLAN] shared worker-default policy and platform Manager identity remain unchanged\n');
 process.stdout.write('[PLAN] rollback removes only the dedicated user and dedicated policy\n');
 process.exit(0);
}

if(mode==='--apply'){
 let secret=env.CHATFLOWS_TASK_FS_SECRET_KEY?.trim();
 if(!secret){secret=crypto.randomBytes(32).toString('base64url');saveSecret(envFile,secret);}
 if(secret.length<32)throw new Error('CHATFLOWS_TASK_FS_SECRET_KEY must be at least 32 characters');
 const shell=`set -eu
test -n "\${AGENTTEAMS_MINIO_USER:-}" && test -n "\${AGENTTEAMS_MINIO_PASSWORD:-}"
test "$access" != "$AGENTTEAMS_MINIO_USER" && test "$access" != "\${AGENTTEAMS_FS_ACCESS_KEY:-}"
config_dir="$(mktemp -d)"; policy_file="$(mktemp)"; trap 'rm -rf "$config_dir"; rm -f "$policy_file"' EXIT
export MC_CONFIG_DIR="$config_dir"
mc alias set task-admin "$endpoint" "$AGENTTEAMS_MINIO_USER" "$AGENTTEAMS_MINIO_PASSWORD" >/dev/null
printf '%s' "$policy_json" >"$policy_file"
mc admin policy create task-admin "$policy_name" "$policy_file" >/dev/null
mc admin user add task-admin "$access" "$secret" >/dev/null
mc admin policy attach task-admin "$policy_name" --user "$access" >/dev/null
`;
 runController(shellVariables(env,secret,policy(EXPECTED_BUCKET,EXPECTED_PREFIX))+shell);
 process.stdout.write('[PASS] dedicated fixed-Team MinIO user and policy configured; secret retained only in mode-0600 env\n');
 process.exit(0);
}

if(mode==='--check'){
 const secret=required(env,'CHATFLOWS_TASK_FS_SECRET_KEY');
 const shell=`set -eu
config_dir="$(mktemp -d)"; marker="$(mktemp)"; trap 'rm -rf "$config_dir"; rm -f "$marker"' EXIT
export MC_CONFIG_DIR="$config_dir"
mc alias set task-user "$endpoint" "$access" "$secret" >/dev/null
uuid="$(cat /proc/sys/kernel/random/uuid)"; allowed="$prefix/task-$uuid/meta.json"
printf 'chatflows-task-storage-check' >"$marker"
mc cp "$marker" "task-user/$bucket/$allowed" >/dev/null
test "$(mc cat "task-user/$bucket/$allowed")" = 'chatflows-task-storage-check'
mc rm "task-user/$bucket/$allowed" >/dev/null
if mc cp "$marker" "task-user/$bucket/shared/tasks/task-$uuid/meta.json" >/dev/null 2>&1; then mc rm --force "task-user/$bucket/shared/tasks/task-$uuid/meta.json" >/dev/null 2>&1 || true; exit 41; fi
sibling="teams/another-team/shared/tasks/task-$uuid/meta.json"
if mc cp "$marker" "task-user/$bucket/$sibling" >/dev/null 2>&1; then mc rm --force "task-user/$bucket/$sibling" >/dev/null 2>&1 || true; exit 42; fi
duplicated_root="agentteams/agentteams-storage/$prefix/task-$uuid/meta.json"
if mc cp "$marker" "task-user/$bucket/$duplicated_root" >/dev/null 2>&1; then mc rm --force "task-user/$bucket/$duplicated_root" >/dev/null 2>&1 || true; exit 43; fi
`;
 runController(shellVariables(env,secret)+shell);
 process.stdout.write('[PASS] dedicated MinIO identity can access only the fixed Team object-key prefix; three out-of-scope writes denied\n');
 process.exit(0);
}

const secret=env.CHATFLOWS_TASK_FS_SECRET_KEY?.trim()||'unused-rollback-secret-value';
const shell=`set -eu
test -n "\${AGENTTEAMS_MINIO_USER:-}" && test -n "\${AGENTTEAMS_MINIO_PASSWORD:-}"
config_dir="$(mktemp -d)"; trap 'rm -rf "$config_dir"' EXIT; export MC_CONFIG_DIR="$config_dir"
mc alias set task-admin "$endpoint" "$AGENTTEAMS_MINIO_USER" "$AGENTTEAMS_MINIO_PASSWORD" >/dev/null
mc admin user remove task-admin "$access" >/dev/null 2>&1 || true
mc admin policy remove task-admin "$policy_name" >/dev/null 2>&1 || true
`;
runController(shellVariables(env,secret)+shell);
saveSecret(envFile,'');
process.stdout.write('[PASS] dedicated MinIO user and policy removed; shared identities and policies unchanged\n');
