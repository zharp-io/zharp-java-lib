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
  {
    name: 'boot3-starter',
    framework: 'spring-boot',
    port: 18082,
    db: true,
    logs: true,
    route: 'GET /users/{id}',
    paths: [...ALL, 'POST /users', '/dup-caught'],
    command: () => ['java', ['-jar', `${apps}/boot3-starter/build/libs/boot3-starter.jar`]],
  },
  {
    name: 'boot3-webflux',
    framework: 'spring-boot',
    port: 18083,
    logs: true,
    route: 'GET /users/{id}',
    paths: ALL,
    command: () => ['java', [`-javaagent:${agent}`, '-jar', `${apps}/boot3-webflux/build/libs/boot3-webflux.jar`]],
  },
];
