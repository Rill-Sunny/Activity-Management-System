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

## Ship

`POST /publisher/product-orders/{orderId}/ship`

```json
{ "orderItemId": 2001, "quantity": 2 }
```

The authenticated Publisher must own the order, and the order must be paid. The request quantity must equal the full remaining unrefunded quantity of that still-unshipped order item. The server records `shipped_at`; callers cannot set event times. Each SKU line moves independently to `SHIPPED`.

## Receive

`POST /product-orders/{orderId}/receive`

```json
{ "orderItemId": 2001, "quantity": 2 }
```

Only the order buyer can confirm receipt. The quantity must match all units recorded as shipped for that item. The server records `received_at`; repeated receipt confirmation is rejected. No carrier label, tracking code, or real logistics service is created.

## Apply for product after-sales

`POST /product-after-sales`

```json
{
  "requestId": "return-001",
  "orderId": 1001,
  "items": [
    { "orderItemId": 2001, "quantity": 2, "reason": "NO_REASON", "simulateResalable": true }
  ]
}
```

The buyer identity comes from authentication. Each order item can appear once per request and must belong to the paid order. Reasons are `NO_REASON`, `QUALITY_PROBLEM`, `TICKET_QUALIFICATION_LOST`, and `ACTIVITY_CANCELLED`. `simulateResalable` is an optional local inspection input that defaults to `true`; it does not assert a real warehouse inspection. `NO_REASON` requires `STANDARD_7D`, a received item, a resalable simulation result, and database time in `[received_at, received_at + 7 days)`. The endpoint rejects the exact seven-day endpoint. `SPECIAL_NO_REASON_RETURN` rejects only `NO_REASON`; the other reasons remain available.

The endpoint simulates return and inspection for received goods, then simulates a refund in one transaction. All requests first atomically reserve detail quantities using a conditional database update. Unshipped refunds restore SKU stock; returned goods restore SKU stock only when inspection passes and the simulation says they are resalable. `simulateInspectionPassed` and `simulateResalable` are optional local simulation inputs. Inspection defaults to passed; resalability defaults to the inspection result. The combination `simulateInspectionPassed=true, simulateResalable=false` models an accepted inspection with goods that cannot be resold. Successful refunds always restore the Product purchase count. A failed step rolls back the main transaction's application and resource changes, then persists a queryable `FAILED` request/detail with its failure reason in a separate transaction. Replaying the same buyer/requestId/payload safely retries that failed record; changed payload returns `409 IDEMPOTENCY_CONFLICT`. Validation rejections before processing do not create an after-sale record.

The response reports each detail's `returnedQuantity`, `refundedQuantity`, server `returnedAt`, `inspectionResult`, and `resalable` result.
