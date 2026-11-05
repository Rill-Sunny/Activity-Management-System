# Product order preview and confirmation API

Both endpoints require an authenticated account. The buyer ID comes from `CurrentUserProvider`; a request body containing buyer, Publisher, price, total amount, or qualification result is rejected.

## Preview

`POST /product-orders/preview`

```json
{
  "items": [
    { "skuId": 101, "quantity": 2 },
    { "skuId": 102, "quantity": 1 }
  ]
}
```

The service checks every SKU and Product, current eligibility, a single Publisher, per-Product shared purchase limits, and exact SKU stock. A repeated SKU is rejected. An out-of-stock SKU remains in the response and is never replaced:

```json
{
  "available": false,
  "totalAmount": 297.00,
  "items": [
    {
      "skuId": 101,
      "requestedQuantity": 2,
      "availableStock": 0,
      "status": "INSUFFICIENT_STOCK"
    }
  ]
}
```

Price, stock, and existing Product purchase counts are ordinary reads. Preview does not create an order or change `sku.remains_stock` or `user_product_purchase`.

## Confirm

`POST /product-orders/confirm`

```json
{
  "requestId": "client-generated-unique-id",
  "items": [
    { "skuId": 102, "quantity": 2 }
  ]
}
```

The confirmation list is revalidated from current database state. The server reads prices, derives the Publisher, computes the amount, and stores price, catalog, qualification-scope, and policy snapshots. For `SPECIAL_NO_REASON_RETURN`, set `acknowledgeSpecialPolicy` to `true` on that SKU line; normal-policy lines must omit it or leave it false.

Success returns `201 Created`; a matching retry returns the original order with `200 OK`. The same buyer and `requestId` with different SKU, quantity, or policy acknowledgment returns `409 IDEMPOTENCY_CONFLICT`. The order is `PENDING_PAYMENT` and expires 15 minutes after database creation time.

Confirmation checks current stock but does not reserve it. It does not change `remains_stock` or create/update the Product quota row. Payment must recheck and atomically consume both resources in its own transaction.

## Pay

`POST /product-orders/{orderId}/pay` requires the authenticated buyer and has no request body. The server locks the order, reloads its persisted items, rechecks current Product/SKU sale state and qualification, atomically increases Product-level purchase counts and deducts each SKU stock, then marks the order `PAID`. It does not accept buyer, Publisher, amount, timestamp, status, SKU quantities, or quota from the client. Repeating payment for an already paid order returns its stored result without applying resource changes again.

If any SKU is short, all quota and stock changes in the payment attempt roll back. A separate transaction then closes the still-pending order with `closedReason=OUT_OF_STOCK`. The response is `409 PRODUCT_ORDER_OUT_OF_STOCK` with the observed `skuId`, `requestedQuantity`, `availableStock`, and `status=INSUFFICIENT_STOCK`. This is a point-in-time inventory observation; the order’s original items remain unchanged. To try another selection, preview and confirm it under a new `requestId`.

Ticket-qualified purchases use the shared account × Activity qualification Guard. `SESSION` qualification checks a valid ticket for the exact configured session. A later ticket refund does not automatically refund or close a paid product order in this phase; Day 20 handles that after-sales workflow.

Expired pending product orders are closed by the product-order expiration scan using database time. Payment retries only classified deadlocks or lock wait timeouts, up to three complete transaction attempts; stock and business errors are not retried.

Common business errors include `SKU_NOT_FOUND`, `PRODUCT_NOT_PURCHASABLE`, `SKU_NOT_PURCHASABLE`, `PRODUCT_QUALIFICATION_DENIED`, `PRODUCT_PUBLISHER_MISMATCH`, `PRODUCT_PURCHASE_LIMIT_EXCEEDED`, `INSUFFICIENT_SKU_STOCK`, `PRODUCT_POLICY_ACK_REQUIRED`, and `IDEMPOTENCY_CONFLICT`.
