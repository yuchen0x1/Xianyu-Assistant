import { request } from '@/utils/request';

// 商品发布模板
export interface GoodsTemplate {
  id?: number;
  title: string;
  description?: string;
  pricePlaceholder?: string;
  imageUrls?: string;
  categoryId?: string;
  remark?: string;
  createTime?: string;
  updateTime?: string;
}

// 模板列表响应
export interface TemplateListResponse {
  list: GoodsTemplate[];
  totalCount: number;
  pageNum: number;
  pageSize: number;
}

// 批量上架结果
export interface TemplatePublishResult {
  templateId: number;
  success: boolean;
  itemId?: string;
  message?: string;
}

// 保存模板（新建/更新）
export function saveTemplate(data: GoodsTemplate) {
  return request<GoodsTemplate>({
    url: '/templates/save',
    method: 'POST',
    data
  });
}

// 分页查询模板
export function listTemplates(data: { pageNum?: number; pageSize?: number }) {
  return request<TemplateListResponse>({
    url: '/templates/list',
    method: 'POST',
    data
  });
}

// 删除模板
export function deleteTemplate(id: number) {
  return request<string>({
    url: '/templates/delete',
    method: 'POST',
    data: { id }
  });
}

// 导入CSV创建模板
export function importTemplateCsv(content: string) {
  return request<{ successCount: number; failCount: number; failures: string[] }>({
    url: '/templates/importCsv',
    method: 'POST',
    data: { content }
  });
}

// 批量上架
export function batchPublishTemplates(data: { accountId: number; templateIds: number[] }) {
  return request<TemplatePublishResult[]>({
    url: '/templates/batchPublish',
    method: 'POST',
    data
  });
}