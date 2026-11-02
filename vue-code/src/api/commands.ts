import { request } from '@/utils/request';

/** 命令参数规格 */
export interface CommandParam {
  name: string;
  type: string;
  required: boolean;
  description?: string;
}

/** 命令描述 */
export interface CommandDescriptor {
  name: string;
  description: string;
  params: CommandParam[];
  needAccount: boolean;
}

/** 获取命令列表（含参数 schema） */
export function listCommands() {
  return request<CommandDescriptor[]>({
    url: '/commands/list',
    method: 'POST',
    data: {}
  });
}

/** 执行命令 */
export function executeCommand(name: string, args: Record<string, unknown>) {
  return request<Record<string, unknown>>({
    url: '/commands/execute',
    method: 'POST',
    data: { name, args }
  });
}
