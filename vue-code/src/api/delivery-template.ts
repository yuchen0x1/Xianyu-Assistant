import { request } from '@/utils/request'

export interface DeliveryTemplateMessage {
  id?: number
  templateId?: number
  sortOrder: number
  messageType: 'TEXT'
  content: string
}

export interface DeliveryTemplate {
  id?: number
  xianyuAccountId: number
  name: string
  enabled: number
  messages: DeliveryTemplateMessage[]
  createTime?: string
  updateTime?: string
}

export function listDeliveryTemplates(xianyuAccountId: number) {
  return request<DeliveryTemplate[]>({
    url: '/delivery-templates/list',
    method: 'GET',
    params: { xianyuAccountId }
  })
}

export function saveDeliveryTemplate(data: DeliveryTemplate) {
  return request<DeliveryTemplate>({
    url: '/delivery-templates/save',
    method: 'POST',
    data
  })
}

export function deleteDeliveryTemplate(id: number, xianyuAccountId: number) {
  return request<string>({
    url: '/delivery-templates/delete',
    method: 'DELETE',
    params: { id, xianyuAccountId }
  })
}
