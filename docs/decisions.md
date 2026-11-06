# 架构决策

## ADR-001：REST API 的无状态认证与 CSRF

状态：已实施。

### 决策

本项目 API 使用显式 Authorization Bearer Token，不依赖浏览器自动携带的认证 Cookie，因此当前 REST API 采用 stateless session 并关闭 CSRF；如果以后改为 Cookie 认证，需要重新评估。

### 适用条件

客户端通过 `Authorization: Bearer <JWT>` 显式提交令牌。服务端由 Spring Security Resource Server 调用 `JwtDecoder` 验证令牌，不从 Cookie、URL 查询参数或表单字段中读取认证令牌，也不自动将 JWT 写入 Cookie。

`SecurityFilterChain` 使用 `SessionCreationPolicy.STATELESS`，不通过 HTTP Session 保存或恢复认证身份。HTTP Basic 和表单登录关闭，受保护接口的身份由当前请求的 Bearer Token 提供。

JWT 使用 HS256。配置 `security.jwt.secret-base64` 从环境变量 `JWT_SECRET_BASE64` 读取密钥，Base64 解码后至少为 32 字节。密钥由安全随机数生成器产生，不使用口令充当密钥；项目不内置默认密钥，缺失、格式错误或长度不足都会阻止应用启动。

`JwtEncoder` 和 `JwtDecoder` 使用 Spring Security 的 Nimbus 实现。验签仅接受 HS256，并校验时间声明；令牌必须包含 `iat` 和 `exp`。受保护请求根据 `sub` 查询当前用户，要求账号为 `ACTIVE` 且令牌角色与数据库一致，再将 `role` 映射为 `ROLE_` 权限。

### 原因与重新评估条件

CSRF 风险与浏览器是否会自动附带认证凭据有关，不能仅凭“前后端分离”判断。仅设置无状态会话也不足以作为关闭 CSRF 的依据，例如浏览器仍可能自动发送 Cookie 或 HTTP Basic 凭据。

如果以后引入认证 Cookie、基于 Cookie 的刷新令牌或其他浏览器自动携带的认证方式，需要重新评估受影响接口的 CSRF 防护，再调整安全配置。

参考：[Spring Security 会话管理](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html)、[Spring Security CSRF 防护](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)。

## ADR-002：白名单当前资格与发布冻结的边界

状态：名单管理、资格锁协调、核心配置冻结、创建票单及完整支付的资格接入已实施。预约及 D12 完整业务回归随对应阶段实施。

### Whitelist semantics

1. 没有配置行表示未配置。场次未配置时继承活动规则，两级均未配置时采用公开规则。
2. 配置行存在且成员数为零表示显式空名单。所有账号都无法通过该名单，包括发布者和活动所有者。
3. 场次配置完全覆盖活动配置。场次名单为空或不包含当前账号时，均不回退到活动名单。
4. 所属发布者可以维护已发布活动的白名单。允许状态为 `DRAFT`、`PUBLISHED`，终态名单修改被拒绝。
5. 名单变化不自动使已支付门票或已成功预约失效。
6. 每次新增资格行为都读取当前适用名单。待支付订单在支付时复查，取消或退款后重新参与也重新检查。

第 5、6 项是交易业务必须遵守的约束。当前创建票单与待支付支付均在自身事务中查询当前资格并协调名单修改，预约及已成交记录的完整回归留到对应阶段。创建订单时保存的 `eligibility = true` 不能作为支付授权。

创建订单、支付、预约及取消后重新参与，各自调用 `QualificationService`。账号 ID 从可信身份上下文取得，作用域从受校验的活动、场次或订单关系取得。资格通过后，调用方继续校验状态、时间、库存与限购。

预约与创建票单调用 `check(activityId, sessionId)`，支付调用 `checkAgain(activityId, sessionId)`。两个入口要求已有业务事务，从 `CurrentUserProvider` 取得账号 ID，并返回通过检查的可信用户 ID。资格不通过统一返回 `403 QUALIFICATION_DENIED`。`checkAgain` 重新读取当前账号、父子关系和名单，不接收先前的资格结果。`canParticipate` 保留为内部布尔查询，三个入口共用同一判断实现。

### 并发决策

资格检查按账号、Activity、Session、配置、成员的顺序执行 `FOR SHARE` 当前读；名单管理沿用 Activity 根记录 `FOR UPDATE`。两者通过同一 Activity 行协调，多个资格检查共享读取，无需每次支付排他锁整个活动。

谁先取得 Activity 锁，谁的事务先完成对应业务阶段。名单先修改并提交时，等待的资格检查读取新名单；资格检查先取得共享锁时，名单修改等待该业务事务结束。Activity 行同时保护“配置尚不存在”的公开或继承状态。

资格调用加入业务动作所属事务，锁保持至该事务结束。独立调用返回的布尔值只表示这一次检查，不能作为另一个支付事务的长期许可。锁定查询清空 MyBatis 本地缓存并禁用二级缓存，避免复用先前查询结果；当前读也避免已有事务快照中的旧名单。

### 发布冻结决策

发布后的类型、开放窗口、容量、限购、选座模式、区域容量、票价和座位配置冻结。场次开始与结束时间的普通修改也被拒绝，专用改期流程留到 Day 22。当前配置新增入口在根记录锁后校验 `DRAFT`，发布后返回 `409 ACTIVITY_NOT_DRAFT`。尚未开放的普通编辑返回 `404` 或 `405`。

白名单是可变参与资格，所属活动为 `DRAFT` 或 `PUBLISHED` 时允许所属发布者维护。Service 先锁定 Activity 根记录，再检查当前状态，随后操作白名单配置与成员。活动为 `CANCELLED`、`FINISHED` 或其他状态时返回 `409 WHITELIST_NOT_EDITABLE`。名单维护不撤销既有交易记录。

具体锁顺序、冻结字段及自动化验证见 [locking.md](locking.md)。MySQL 机制依据：[锁定读](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)。

AT-04、AT-16 的当前验收范围及测试入口见 [当前架构与阶段验收](architecture.md)。配置状态与生命周期边界见 [状态机](state-machines.md)。

## ADR-003：创建票单按原明细实现请求幂等

状态：已实施，采用方案 A，不增加请求摘要列或数据库迁移。

### 决策

`(buyer_id, request_id)` 标识一次创建操作。读取原订单及明细，比较 `sessionId` 和每项的 `zoneId`、`allocationMode`、`requestedSeatId`、`quantity`、`acceptNonAdjacent`。明细按上述字段排序后逐项比较；输入顺序不影响结果，重复行及每行数量保留，不合并拆分购买行，以保留按行的自动配座与相邻偏好。

相同意图返回 `200` 和原订单，不同意图返回 `409 IDEMPOTENCY_CONFLICT`；首次创建返回 `201`。重放保留已存价格、ID、期限及当前状态，不重新执行新订单的销售窗口、名单或库存预检查。账号仍须有效，未来支付仍检查当前资格和交易条件。

### 原因与事务边界

唯一键只能阻止相同购买者、相同请求 ID 再次插入，无法判断新载荷是否与原载荷一致。总额也不能标识购买意图：两张 A 区票与一张 B 区票可能金额相同。现有不可变购买明细已完整保存需要比较的字段，因此直接比较可复用当前模型，避免额外摘要列、迁移及摘要规范版本管理。

协调入口 `TicketOrderService` 挂起调用方事务。首次创建与重放查询通过不同 Spring Bean 运行独立 `REQUIRES_NEW`、`READ_COMMITTED` 事务，重放事务为只读。订单创建独立提交，不能视作外部调用方事务的可回滚子步骤。这样既可在唯一键失败事务完整回滚后重读，也不沿用调用方 `REPEATABLE_READ` 的旧快照。主表与全部明细仍在同一创建事务中原子提交或回滚。

唯一键作为并发最后防线。竞争失败者在事务代理完成回滚后重查原单，再比较载荷。迟到请求发生创建业务或资格错误时也重查一次，处理另一请求已完成创建的情况；查不到原单就继续抛出原错误。技术故障继续向外传播，不返回伪造成功结果。

重放是读取既有结果，不是新的资格行为。后续支付入口不复用创建时的资格结论，仍按 ADR-002 重新查询当前名单。具体请求、错误与验收见 [创建票单 API](ticket-orders.md#创建请求幂等方案-a)，事务竞争见 [锁策略](locking.md#ticket-order-idempotency)。

## ADR-004：下单不预占与支付时数据库权威

状态：创建阶段与支付时资源取得、支付幂等已实施。

Ticket order creation does not reserve inventory, seats, or purchase quota.

The database is authoritative at payment time.

requestId idempotency protects creation only. Payment idempotency is a separate concern.

创建只原子写入待支付订单与全部明细。当前库存及限购读取用于预检查，不扣减区域余量、不改变座位状态、不增加有效购票计数、不初始化缺失计数行，也不签发 Ticket。自动配座尚未确定座位；自主选座仅记录指定 Seat 的购买意图。

其他用户可以在订单创建后先取得资源，因此创建成功不保证支付成功。后续支付事务必须根据数据库当前时间和数据，重新核验订单有效期、当前资格、场次状态、有效购票数、库存及座位，并原子完成资源取得、Ticket 生成和订单状态更新。支付旧单不重新套用新订单创建窗口。

`UNIQUE(buyer_id, request_id)` 与原明细比较仅保证同一次创建不会生成第二张订单。它不约束同一订单的支付次数，也不会自动防止重复扣库存、重复增加购票数或重复签发 Ticket。支付必须另外设计订单状态条件更新与事务幂等。

真实 MySQL 验收分别使用 `AUTO_ASSIGN`、`SELECT_SEAT`，在余量 100、100 个 `AVAILABLE` Seat、已存有效购票计数为 0 的条件下创建两条明细。前后完整资源快照保持一致，只有订单增加一行、明细增加两行。测试同时保留计数行缺失时不初始化的覆盖，见 [下单不预占证据](ticket-orders.md#下单不预占的数据库证据)。

## ADR-005：支付资源以订单优先的统一锁顺序协调

状态：订单锁、资源原子 Mapper、配座组件及完整支付已实施。

支付使用 `Order → 当前资格 → 账号场次限购 → Zone 升序 → Seat 升序 → Ticket → Order PAID`。本决策将先前文档中“资格先于订单”的支付设计统一为本轮确定的订单优先。资格组件内部的账号、Activity、Session、配置与成员顺序保持不变；创建新订单仍在资格检查后 INSERT，不锁定已存订单。

限购初始化不重置已有计数，额度和库存用条件 UPDATE 判断是否取得。配座先按 ID 升序锁齐全部目标 Zone，再确定全部候选，最后按全单 Seat ID 升序占用。实际锁顺序不跟随选座优先级，避免多区域及相反输入顺序导致交叉等待。

自动配座按 `allocation_priority`、`row_no`、`seat_no`、`seat_id` 尝试连座起点，在同排寻找连续递增的 Seat 编号。没有连座时只有订单已接受非连座才使用同优先序的分散候选；不重新征求偏好、不替换指定座位，不承诺按 HTTP 到达顺序公平成交。

资源组件使用 `MANDATORY` 加入调用方事务，配座要求显式 `READ_COMMITTED`，Zone 锁覆盖读取与写入窗口。当前可用座位查询禁用缓存，防止等待锁后仍使用早期结果。所有业务失败、死锁或锁超时都退出并回滚整笔事务，未来需要重试时在事务外重新开始，不能保留部分额度、库存或 Seat 写入。

未来退款、关闭和取消需协调同一顺序，不能持有 Activity 排他锁再等待支付订单锁。资源组件没有自行完成资格、支付状态或有效期授权；可信调用方仍须先锁定订单并检查。详见 [锁策略](locking.md#ticket-payment-resources)和[支付资源验收](payment-resources.md)。

## ADR-006：支付骨架强制回滚与旧单支付期限

状态：Day 6 历史决策。骨架的强制回滚保护已由 ADR-007 的完整支付事务替代，旧单支付期限规则继续适用。

`TicketPaymentService.pay(orderId)` 在一个 `READ_COMMITTED`、`REQUIRED` 事务中锁定订单，核验买家与当前 ACTIVE 账号、待支付状态及有效期，调用 `QualificationService.checkAgain` 读取当前名单，随后取得限购额度、扣减库存并配座。库存扣减由配座协调器在 Zone 锁内统一执行，支付骨架不重复扣减。

资源步骤通过后仍在事务内抛出 `PaymentFlowIncompleteException`，确保未实现 Ticket 与 PAID 时无法提交半笔支付。生产环境不注册支付端点。异常从事务代理退出后已完成回滚；加入外层事务时标记 rollback-only，外层不能捕获异常后提交资源。Day 7 只有补齐完整事务与对应测试后才移除此保护。

活动销售窗口约束创建新单。支付旧单以数据库时间检查订单未过期、场次尚未开始且未取消、活动仍可交易，以及当前账号与名单资格；不重用创建阶段的 `[open_time, close_time)` 条件。资格等待后及配座后复核时间，最终 PAID 条件更新仍须在 Day 7 补上数据库期限保护。

Day 6 骨架中的业务拒绝只回滚支付尝试，不关闭订单。Day 7 的明确缺货关闭规则由 ADR-007 取代本条阶段限制。统一失败原因、事务实验与范围见 [支付资源验收](payment-resources.md)。

## ADR-007：完整支付与缺货后独立关闭

状态：Day 7 已实施，不增加表或列。

`POST /ticket-orders/{orderId}/pay` 不接收购买者、金额、时间、Ticket 或实际 Seat 等客户端字段。身份来自认证上下文，购买意图与价格来自已存订单及明细。

`TicketPaymentService` 使用 `NOT_SUPPORTED` 作为协调入口。独立 `REQUIRES_NEW`、`READ_COMMITTED` 支付事务先锁 Order，再核对买家和 ACTIVE 账号。已 PAID 时读取原 Ticket，不重新应用名单或再占资源；待支付时检查数据库期限、生命周期及当前名单，再按既定锁顺序取得限购、全部 Zone 和 Seat。Ticket 单价沿用 `ticket_order_item.unit_price`，逐项 Ticket 数量必须等于明细数量，总额必须与订单快照一致。Ticket 与最终 PAID 状态和资源一起提交。

最终 UPDATE 同时保护 PENDING_PAYMENT、订单期限、Session 状态及开始时间、Activity 状态，不检查停售时间。语句返回后再次读取数据库时间，防止沿用等待前的 `CURRENT_TIMESTAMP` 继续提交。语句时间语义依据：[MySQL 时间函数](https://dev.mysql.com/doc/refman/8.4/en/date-and-time-functions.html)。

ORD-05 只对 `INSUFFICIENT_INVENTORY` 要求关闭旧单。支付事务先完全回滚，代理退出后调用 `TicketPaymentFailureService` 另开短事务并重新锁 Order。仍待支付时条件更新 CLOSED 并写明确原因；另一请求已支付时返回原 Ticket，不覆盖 PAID。关闭本身失败继续传播技术错误，不伪造已经关闭。

限购、名单、座位、连座和期限拒绝保留各自语义，不套用缺货关闭。数据库断连、SQL 错误和未知异常不转换成库存不足。当前不实现定时过期、用户取消、死锁自动重试、Redis、RabbitMQ 超时或退款。

支付事务和缺货关闭都独立于调用方外围事务。调用者不能用外围回滚撤销已经完成的支付。原 `requestId` 仍只保护创建幂等；支付幂等依靠当前订单状态、行锁与数据库原子提交。接口见 [模拟支付 API](api/ticket-payments.md)，证据见 [支付验收](payment-resources.md)。

## ADR-008：免费预约有效唯一键与独立名额

状态：Schema V2.4 与 V4 迁移已冻结，创建与取消 API 已按契约接入，数据库原子性、时间与资格边界均有验证。

`free_reservation` 采用与 Ticket 相同的 VIRTUAL active key 思路：ACTIVE 时生成 session_id，CANCELLED 时生成 NULL，数据库唯一索引为 `(user_id, active_session_id)`。取消更新旧行并记录取消时间，重新预约 INSERT 新行，保留全部历史。同账号可预约不同场次。

独立当前表加历史表同样可行，但增加当前指针与历史的双表维护。本阶段单表状态历史配合生成唯一键已满足需求。普通 `(user_id, session_id)` 唯一键阻断重新预约；加入 status 的三列唯一键又限制多条取消历史，均不采用。

名额位于独立 `free_session_quota`，与付费 Zone、Seat、Ticket 分离。capacity 是发布冻结快照，remaining 为运行库存。资格检查持有 Session 共享锁，独立名额行避免接着更新同一 Session 计数产生锁升级。

首次预约事务按已发布 FREE 场次惰性初始化 quota，主键冲突只做 no-op，不覆盖剩余量。条件扣 1 和预约 INSERT 同事务；有效唯一键冲突或其他异常回滚名额。取消采用 quota → reservation 的统一写锁顺序，状态条件成功后只返还一次，失败整体回滚。

数据库约束负责单行形态与有效唯一性，Service 负责身份、FREE 归属、当前资格、时间及生命周期；跨表对账验证 remaining + ACTIVE 数量 = capacity。设计、原子 SQL、替代方案与验证见[免费预约 Schema](free-reservations.md)。

## ADR-009：退款受理保存逐票金额与申请时政策

状态：Day 10 已实施；Schema V2.5、V5 迁移及受理契约冻结。当前入口只产生 PENDING，实际生效属于 Day 11。

本次明确冻结四项决定：

1. 退款资格使用服务端 requestedAt。认证后、等待业务锁前从注入的 Clock 读取一次，按 Asia/Shanghai 保存到 DATETIME(3)；后续处理使用该快照。
2. 普通退款最初 48 小时以 activity.openTime 为起点，窗口为 [openTime, openTime + 48h)，不从付款时间计算。场次开始优先于免费窗口；随后遵循 REF-02 的 30d、14d、10d 阶梯。
3. Java 金额计算使用 BigDecimal，金额 scale=2，数据库使用 DECIMAL。原价来自每张 Ticket.paidPrice。
4. 舍入模式为 RoundingMode.HALF_UP。逐票计算 originalPrice × (1 − feeRate) 后保留两位；实际手续费由原价减已舍入退款金额得到。

退款主申请与逐票明细为一对多。主表保存政策版本、开售时间、场次开始时间与申请时间，明细保存每张票的原价、费率和应退金额。Day 13 将生产时间源由 Clock.system 改为 DatabaseRefundClock，与数据库出票时间一致；测试仍可注入 Fixed Clock 验证精确边界。主表不保存汇总金额，汇总值按 refund_id 对逐票 refund_amount 求和得到。根因、影响与回归见 [阶段测试报告](reports/test-report.md#bug-003刚支付后退款被误判早于出票)。

TicketRefundPolicy.evaluate 接收申请时间、活动开售时间、场次开始时间和 Ticket 成交价，返回是否允许、手续费比例、退款比例、手续费金额、退款金额和拒绝原因。OrdinaryTicketRefundPolicy 只计算这些显式输入，不读取时钟；后续特殊退款可实现同一接口。

申请键为 UNIQUE(buyer_id, request_id)，相同键比较订单、原因和完整票集合。主表冲突退出事务后重新读取；不同键也受逐票 claimed_ticket_id 唯一键保护，同票不能同时处于多条 PENDING、PROCESSING、SUCCEEDED 明细。FAILED 明细保留历史并释放该键。

所有权检查沿 Ticket → TicketOrderItem → TicketOrder，核对指定订单和当前买家。申请主表与全部明细在一个独立 READ_COMMITTED 事务提交。当前阶段不修改门票、订单状态或支付资源。具体字段、锁顺序、API 和证据见 [退款申请契约](ticket-refunds.md)。

错误码分别表达所有权、票状态、场次已开始、退款窗口关闭、重复申请及幂等内容冲突。REFUND_AMOUNT_CALCULATION_ERROR 使用 HTTP 500，表示服务端金额数据或计算问题；它和非法客户端字段的 400 分开，事务完整回滚。Day 10 不执行实际退款、资源恢复或后续异步流程。

## ADR-010：GIFT 操作保留逐座历史，不绑定领取人

V7 新增 gift_inventory_operation 与 gift_inventory_operation_item，采用 UNIQUE(publisher_id, request_id) 保护发布者操作幂等，采用 UNIQUE(operation_id, seat_id) 保护同次操作座位去重。所有关联沿用逻辑引用，不增加物理外键。

当前仅支持 ALLOCATE，持久化状态只有 SUCCEEDED。分配事务已将 Seat 占用、Zone 扣减、主操作和完整 Seat 明细一起提交，失败一起回滚。记录 quantity 不能替代记录具体 Seat；Seat 当前状态也不能替代操作历史。

publisher_id 是操作者，不能解释为赠票领取人。模型不增加领取人平台账号、实名或核销字段，不生成普通 Ticket。未来若增加 RELEASE，必须有来源操作及来源座位明细，不通过改写旧分配历史释放资源。字段、边界和验证见 [GIFT 操作模型](gift-inventory.md)。

## ADR-011：商品域资格、限购与订单边界

日期：2026-11-02；Day 15 补充：2026-11-03；Day 17 Schema 更新：2026-11-05。状态：Day 14 目录、资格与商品订单创建设计已冻结；V8 目录、V9 订单、V10 支付资格 Guard 已落库。V11 和售后 API/Service 已实现。

依照 PRD、ITEM、RET、LINK 已明确的约束：

1. Product 承载发布者、关联活动、购买资格、Product 维度共享限购和两种预设售后模板。SKU 承载规格、价格与独立库存，缺货规格不能由其他 SKU 替代。
2. user_product_purchase 的复合主键为 (user_id, product_id)，全部 SKU 共享 valid_count，没有 user_sku_purchase。
3. 商品资格只取 PUBLIC、TICKET_REQUIRED、PRODUCT_WHITELIST。独立商品成员表与 Product 资格配置配合；商品名单为空即拒绝，不继承活动/场次名单。
4. 商品建单不扣 SKU、不占 Product 限购。订单保存原始单价、资格范围、模板及必要确认快照；模拟支付与售后留到后续阶段。
5. 商品订单包含多 Product、多 SKU，商品支付整单成功或整单失败；不得静默删项、换规格。缺货旧单资源回滚后安全关闭，用户确认新清单后新建订单。
6. SPECIAL_NO_REASON_RETURN 只约束普通无理由退货，不能用它一概拒绝质量问题、失去持票资格或活动取消等特殊处理。
7. 商品订单固定拒绝跨 Publisher，允许同一 Publisher 下跨 Activity 合单；主单 publisher_id 从 SKU → Product 推导。购买清单重复 SKU 直接拒绝，不合并，以便幂等比较保持请求清晰。
8. 预校验和建单均以当前 ACTIVE 用户为准，逐项重查商品、SKU、资格、共享 Product 限购与库存。库存/限购仅作非锁定预检查；待支付订单不承诺库存，不扣 SKU、不创建或更新 user_product_purchase。
9. 同 buyer/requestId 唯一。重试载荷按 skuId → quantity 与特殊政策确认比较；完全相同返回原订单，不同返回 IDEMPOTENCY_CONFLICT。价格、总额、Publisher 和资格仅由服务端查询。
10. 新单状态为 PENDING_PAYMENT，created_at 由数据库生成，expires_at 是 created_at + 15 分钟，paid_at 为 NULL；buyer_id 来自 CurrentUserProvider，publisher_id 从 SKU → Product 推导，total_amount 由服务端按单价快照计算。订单及全部明细在同一 READ_COMMITTED 事务提交。
11. 客户端只可提交 requestId 与明细；buyerId、publisherId、unitPrice、totalAmount 和资格结果属于服务端事实，确认 DTO 拒绝未知字段。订单基础状态、价格快照、幂等、跨 Publisher/资格拒绝、库存和 Product 限购不变、事务回滚均需通过真实 MySQL 最终状态断言验收，覆盖 P01–P14；P05、P06、P10、P11、P12 为重点。

Day 14 的关键决策已冻结如下：

| 决策 | 已冻结规则 | 实现约束 |
|---|---|---|
| 持票范围 | 每个 Product 选择 ACTIVITY 或指定 SESSION，已实现。 | 两种范围按真实有效 Ticket 判定。 |
| D14-Q2：同发布者订单的活动边界 | 已冻结：同 Publisher 可跨 Activity 合单；跨 Publisher 拒绝。 | 多活动持票资格按各 Product 的活动范围分别校验；Day 16 按 activity_id 升序取得共同资格 Guard。 |
| D14-Q3：规则编辑 | 已冻结：Product 首次上架后核心规则不可修改；SKU 价格与库存仅 DRAFT 可设。 | 未来如需改规则，新增版本及订单快照协议，不覆盖旧单依赖的资格与售后事实。 |

字段、单行约束、逻辑引用和后续设计见 [商品域设计](product-domain.md)。Day 14 的订单范围和重复 SKU 决策已冻结；V9 实现订单、订单明细和商品共享限购计数，V10 与 Day 16 接入共同资格 Guard、支付与过期处理。Day 20 限定商品售后联动仍未实现。目录和支付接口见 [商品订单 API](api/product-orders.md)。

## ADR-012：持票资格使用账号活动保护行协调

日期：2026-11-02；Day 16 更新：2026-11-04。状态：V10 与 Day 16 商品支付、票务支付、票务退款已接入共同 Guard；Day 20 售后联动仍未实现。

选用 (user_id, activity_id) 唯一的 user_activity_qualification_guard 作为共同锁对象。它不保存最终资格真相，不以缓存有效票数、资格布尔值或版本号代替 Ticket 当前查询。同一个用户多个活动按 activity_id 升序获取保护行。

商品支付先稳定账号、Activity、Session 和 Product 配置，再取得保护行并重查适用范围的有效票。公开和商品白名单商品不需要 Guard。票务支付和退款处理的有效票变动也取得同一保护行，位置在票务限购、Zone、Seat、Ticket 写锁之前。普通退款受理不改变有效票集合，不提前接入资源锁。`qualification_version` 与成交明细快照用于追踪资格先后，不代替有效 Ticket 查询。

退票先提交时，商品支付重查无票并拒绝；商品先提交时，随后退最后有效票不会抹除已支付商品订单或其明细版本快照。保护行保证串行判定，不能替代可靠联动事件。本轮不自动触发售后；Day 19/20 再要求退票、版本变化与资格变化 Outbox 同事务提交，消费者按明细范围快照和当前有效票独立处理售后，不在退票事务反向锁商品订单。

锁顺序及两种退款/商品支付并发顺序的 MySQL 集成用例见 [商品域并发设计](product-domain.md#最后一张票退款与商品支付)、[锁顺序](locking.md#product-qualification-and-payment-design) 和 [ProductCatalogMySqlIntegrationTest](../src/test/java/com/limitactivitysystem/activity/integration/ProductCatalogMySqlIntegrationTest.java)。

## ADR-013：两种预设模板只表达普通退货政策

状态：Day 14 的数据库枚举、版本与 API 展示已验证；V11 售后 Schema、状态机和模拟处理 API 已实现。

STANDARD_7D 表达项目的常规七日无理由模板；SPECIAL_NO_REASON_RETURN 表达不支持普通无理由退货，并要求后续购买时明确确认。两种模板都不用于一概排除质量问题、限定资格丧失、活动取消等特殊原因。API 展示 refundPolicy、refundPolicyVersion、requiresPolicyAcknowledgement 和服务端说明，不增加一个把所有场景统一关闭的 returnable=false 字段。

售后先区分业务原因，再应用对应流程；不能把 `template == SPECIAL_NO_REASON_RETURN` 直接映射为所有售后拒绝。`NO_REASON` 仅允许 `STANDARD_7D`，质量问题、持票资格丧失和活动取消使用独立原因码。Day 20 的持票资格自动联动仍未接入。两种模板逐项数据库/API 一致性及公开商品资格均在 [Day 14 最终验收](day14-products.md#day-14-acceptance) 留证。

## ADR-014：商品售后按订单明细原子预留退款数量

日期：2026-11-05。状态：V11/V12 与售后状态机已实现，模拟发货/收货和售后 API 已接入。

履约与 `product_order_item` 是一对一关系。履约状态、发货/收货数量及服务器时间直接保存在订单明细，避免再建一张等价的一对一表。申请历史使用 `product_after_sale_request` 和 `product_after_sale_item`。主申请按 `(buyer_id, request_id)` 幂等；每个申请内，同一 `order_item_id` 最多出现一次。SKU、买家、订单和明细之间的逻辑引用由 Service 校验，不增加物理外键。

订单明细保存 `refund_pending_quantity` 与 `refunded_quantity`。前者包含事务内尚未完成的数量，后者只统计已提交成功的退款数量。数据库约束固定为：

```text
0 <= refunded_quantity + refund_pending_quantity <= quantity
```

申请、明细、数量预留、模拟退回/验收/退款及资源恢复在一个 `READ_COMMITTED` 事务中完成。申请必须至少有一条明细，所有明细属于同一订单。每条明细按 `order_item_id ASC` 执行 `UPDATE ... SET refund_pending_quantity = refund_pending_quantity + :quantity WHERE quantity - refunded_quantity - refund_pending_quantity >= :quantity`，并检查影响行数为 1。任一明细预留或后续恢复失败时回滚整笔申请。MySQL `CHECK` 约束作为第二道保护，防止超过购买数量。相同买家和 `request_id` 重放时，载荷相同返回原申请；订单、原因、数量或模拟验收输入不同则返回幂等冲突。

成功事务减少待处理数量并增加成功退款数量。处理阶段技术异常先完整回滚，再由独立事务持久化 `FAILED` 申请、明细和 `failure_reason`；失败记录不占用数量或资源。同一买家、`request_id` 和相同载荷可以安全重试并重开失败记录，变化载荷返回冲突。拒绝于处理前的业务校验不会创建售后记录。

订单明细履约状态为 `UNSHIPPED`、`SHIPPED`、`RECEIVED`。每个明细一次模拟发出其全部未退款数量，一次确认全部已发货数量，以确保明细上的服务器时间对应明确事件；售后仍可申请部分数量。发货、收货及售后都锁同一商品订单和明细。退款成功总是按数量恢复 Product 限购；只有未发货退款，或退回后验收通过且可再售的商品，才恢复 SKU 可售库存。验收失败或不可再售不恢复 SKU 库存。

`NO_REASON` 仅允许 `STANDARD_7D`；需要已收货、模拟验收通过且可再售，并采用 `[received_at, received_at + 7 days)` 的半开窗口，边界等于到期时刻即拒绝。验收通过与是否可再售是独立事实。`SPECIAL_NO_REASON_RETURN` 仅拒绝普通无理由退货。质量问题、持票资格丧失和活动取消可按单独原因表达；持票联动不在本阶段自动触发。

## ADR-015：商品售后时间与明细履约边界

日期：2026-11-05。状态：V11/V12 已实现。

每个订单明细只确认一次完整剩余数量的模拟发货和一次完整已发货数量的模拟收货。V11 的 `shipped_at` 和 `received_at` 因此各表示一个明确的订单明细事件；售后申请仍可部分退款。发货、收货和售后事务共同锁 ProductOrder 与该订单的明细行。

`STANDARD_7D` 的无理由窗口固定为数据库收货时间起 168 小时的半开区间 `[received_at, received_at + 7 days)`。开始时刻接受，精确等于 7 天终点时拒绝，终点之后拒绝。无理由退货还要求已收货并在模拟验收输入中可再售。`SPECIAL_NO_REASON_RETURN` 只拒绝 `NO_REASON`；质量问题、持票资格丧失及活动取消使用独立原因码。Day 20 自动资格联动不由本次实现触发。

## ADR-016：公开缓存使用 generation 和持久化失效任务

日期：2026-11-06。

状态：Cache-Aside、数据库失效任务、提交后立即删除和定时补偿已实现。MySQL/Redis 联合集成用例需要 Redis、测试数据库和 Maven 环境。

活动详情、场次座位布局和商品公开详情使用 Redis Cache-Aside。条目带有 generation；回源前后读取 MySQL generation，Redis Lua 脚本原子比较版本后才写入。正缓存 TTL 为 30–35 秒，空结果 TTL 为 3–5 秒。座位是否可购买、商品真实库存和用户资格不属于缓存授权事实。

领域事务同时递增 `cache_generation` 并插入 `cache_invalidation_task`。`AFTER_COMMIT` 监听器立即删除 Redis 条目，扫描器重试失败任务。Redis 删除成功后、数据库确认任务前若进程退出，重复删除仍安全。MySQL 和 Redis 不能原子提交，因此缓存最多在 Redis 修复前继续服务，正缓存到期上界为 35 秒。支付、退款、库存和资格继续以受事务保护的 MySQL 数据为准。

缓存指标只使用固定缓存类型标签。去重拒绝指标不带标签。Redis 支付去重采用十秒所有者令牌，Lua 只允许令牌所有者释放 Key。Redis 故障时请求继续经过数据库幂等和状态检查。

验证用例与运行结果见 [Redis 测试记录](reports/redis-test.md)。
