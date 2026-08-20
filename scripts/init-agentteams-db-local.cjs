const fs = require('node:fs');
const path = require('node:path');
const { Pool } = require(path.resolve(__dirname, '../agent-core/node_modules/pg'));

const admin = process.env.DATABASE_ADMIN_URL?.trim();
const appPassword = process.env.CHATFLOWS_APP_DB_PASSWORD?.trim();
const runtimePassword = process.env.AGENT_RUNTIME_DB_PASSWORD?.trim();

if (!admin || !appPassword || !runtimePassword) {
  throw new Error('DATABASE_ADMIN_URL/CHATFLOWS_APP_DB_PASSWORD/AGENT_RUNTIME_DB_PASSWORD required');
}

async function main() {
  const pool = new Pool({
    connectionString: admin,
    max: 1,
    ssl: process.env.DATABASE_SSL === '1' ? { rejectUnauthorized: true } : undefined,
  });
  try {
    await pool.query(fs.readFileSync(path.resolve(__dirname, '../agent-core/sql/001_agentteams.sql'), 'utf8'));
    for (const [login, password, parent] of [
      ['chatflows_app_login', appPassword, 'chatflows_app'],
      ['agent_runtime_login', runtimePassword, 'agent_runtime'],
    ]) {
      const exists = await pool.query('select exists(select 1 from pg_roles where rolname=$1) as exists', [login]);
      const statement = exists.rows[0].exists
        ? 'alter role %I login inherit nosuperuser nocreatedb nocreaterole noreplication password %L'
        : 'create role %I login inherit nosuperuser nocreatedb nocreaterole noreplication password %L';
      const formatted = await pool.query('select format($1::text,$2::text,$3::text) as sql', [statement, login, password]);
      await pool.query(formatted.rows[0].sql);
      await pool.query(`grant ${parent} to ${login}`);
    }
    process.stdout.write('[PASS] initialized AgentTeams schema and least-privilege LOGIN roles\n');
  } finally {
    await pool.end();
  }
}

main().catch(error => {
  process.stderr.write(`${error instanceof Error ? error.stack || error.message : String(error)}\n`);
  process.exit(1);
});
