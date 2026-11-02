import { request } from '@/utils/request'

export type DeliveryBlockRuleCode =
  | 'buyer_credit_zero'
  | 'buyer_has_order'
  | 'buyer_unconfirmed'

export interface DeliveryBlockRule {
  ruleCode: DeliveryBlockRuleCode
  ruleName: string
  ruleDescription: string
  enabled: number
  priority: number
  blockReason: string
  excludedItemIds: string[]
  config: {
    threshold?: number
    min_count?: number
    same_item_only?: boolean
  }
}

export function getDeliveryBlockRules(xianyuAccountId: number) {
  return request<DeliveryBlockRule[]>({
    url: '/delivery-block-rule/list',
    method: 'POST',
    params: { xianyuAccountId }
  })
}

export function saveDeliveryBlockRules(
  xianyuAccountId: number,
  rules: DeliveryBlockRule[]
) {
  return request<DeliveryBlockRule[]>({
    url: '/delivery-block-rule/save',
    method: 'POST',
    data: { xianyuAccountId, rules }
  })
}
