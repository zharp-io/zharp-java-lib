// The test matrix: how each app starts and what it can show.
import { fileURLToPath } from 'node:url';

const repo = fileURLToPath(new URL('..', import.meta.url)).replace(/[\/]$/, '');
const agent = `${repo}/zharp-agent/build/release/zharp-agent.jar`;
const apps = `${repo}/e2e/apps`;
const ALL = ['/hello', '/users/7', '/boom', '/handled', '/logged', '/thread-crash'];

export const SCENARIOS = [
  {
    name: 'boot3-mvc',
    framework: 'spring-boot',
    port: 18081,
    db: true,
    logs: true,
    route: 'GET /users/{id}',
    paths: [...ALL, 'POST /users', '/dup-caught'],
    command: () => ['java', [`-javaagent:${agent}`, '-jar', `${apps}/boot3-mvc/build/libs/boot3-mvc.jar`]],
  },
];
