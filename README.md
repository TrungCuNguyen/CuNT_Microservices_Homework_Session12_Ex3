# Hệ Thống Microservices: Redis Cache & Kafka Event-Driven Architecture

Dự án bao gồm 3 bài toán kiến trúc Microservices quan trọng:
1. **Bài toán 1: Tối ưu hóa hiển thị sản phẩm với Redis Cache** (`product-service` - Port 8081)
2. **Bài toán 2: Chống bán vượt (Overselling) trong Flash Sale với Redisson Distributed Lock** (`product-service` - Port 8081)
3. **Bài toán 3: Bất đồng bộ hóa luồng Đặt hàng với Apache Kafka** (`order-service` - Port 8082, `email-service` - Port 8083, `inventory-service` - Port 8084)

---

## 1. Cấu Trúc Tổng Thể Microservices

```
ex_ss12_micro/
├── .gitignore
├── docker-compose.yml              # Khởi chạy Redis (6379), Kafka (9092), Postgres (5432)
├── settings.gradle                 # Quản lý 4 module services
├── gradlew / gradlew.bat
├── requests.http                   # File test API sẵn sàng cho VS Code & IntelliJ IDEA
├── README.md
├── product-service/                # [Bài toán 1] Cache-Aside, Redis Serialization, TTL 30m, @CacheEvict
├── order-service/                  # [Bài toán 2] Kafka Producer: Bắn OrderCreatedEvent vào topic 'order-events'
├── email-service/                  # [Bài toán 2] Kafka Consumer (email-group): Gửi email xác nhận
└── inventory-service/              # [Bài toán 2] Kafka Consumer (inventory-group): Trừ kho sản phẩm
```

---

## 2. Chi Tiết Kỹ Thuật

### Bài toán 1: Redis Cache-Aside Pattern (`product-service`)
- **Key Serializer**: `StringRedisSerializer`
- **Value Serializer**: `GenericJackson2JsonRedisSerializer` (JSON format chuẩn, hỗ trợ `JavaTimeModule`)
- **TTL**: **30 phút** (`Duration.ofMinutes(30)`)
- **@Cacheable**: Lần 1 truy vấn Database (~200ms) rồi nạp vào Redis; các lần sau lấy từ Redis (<10ms).
- **@CacheEvict**: Tự động xóa cache Redis khi quản trị viên cập nhật (`PUT /api/v1/products/{id}`) hoặc xóa sản phẩm.

### Bài toán 2: Khóa phân tán Redisson Distributed Lock (`product-service`)
- **Bối cảnh**: Trong đợt Flash Sale, sản phẩm chỉ còn 1 chiếc nhưng 100 người cùng bấm mua cùng một thời điểm. Không có khóa phân tán sẽ gây lỗi bán vượt tồn kho (Overselling / Race condition).
- **Yêu cầu kỹ thuật**:
  - Dùng **Redisson** tạo khóa phân tán với định dạng key: `lock:product:{id}`.
  - Khi đặt mua (`POST /api/v1/products/{id}/purchase`), luồng phải giành được khóa mới được phép kiểm tra tồn kho và trừ số lượng.
  - Luôn mở khóa (`unlock`) trong khối `finally` để tránh deadlock/treo hệ thống.
  - **Kết quả mong muốn**: Chỉ đúng số lượng người mua bằng số lượng tồn kho thực tế thành công, còn lại nhận thông báo: `"Sản phẩm đã hết hàng"`.

### Bài toán 3: Kafka Event-Driven Architecture (`order-service` / `email-service` / `inventory-service`)
- **Bối cảnh**: Khi khách hàng bấm "Đặt hàng", nếu thực hiện đồng bộ (lưu đơn, trừ kho, gửi mail) khách hàng sẽ phải chờ rất lâu.
- **Giải pháp**:
  - **`order-service` (Producer)**: Lưu đơn hàng vào DB với trạng thái `CREATED`, bắn sự kiện `OrderCreatedEvent` vào Kafka topic `order-events` và phản hồi ngay `HTTP 201 Created` cho khách hàng mà không cần chờ.
  - **`email-service` (Consumer)**: Lắng nghe topic `order-events` với `group-id: email-group` để gửi email xác nhận.
  - **`inventory-service` (Consumer)**: Lắng nghe topic `order-events` với `group-id: inventory-group` (khác group-id) để thực hiện trừ kho độc lập.
- **Mô hình**: Publish/Subscribe pattern đảm bảo tính mở rộng cao và trải nghiệm người dùng tức thì.

---

## 3. Hướng Dẫn Khởi Chạy

### Bước 1: Khởi động Hạ Tầng (Redis, Kafka, PostgreSQL)
```bash
docker compose up -d
```
Lệnh trên khởi chạy:
- **Redis**: port `6379`
- **Kafka Broker (KRaft mode)**: port `9092`
- **PostgreSQL**: port `5432`

### Bước 2: Khởi động các Services
Mở từng terminal để chạy các service tương ứng:
```bash
# Terminal 1: Chạy Product Service (Port 8081)
.\gradlew.bat :product-service:bootRun

# Terminal 2: Chạy Order Service (Port 8082)
.\gradlew.bat :order-service:bootRun

# Terminal 3: Chạy Email Service (Port 8083)
.\gradlew.bat :email-service:bootRun

# Terminal 4: Chạy Inventory Service (Port 8084)
.\gradlew.bat :inventory-service:bootRun
```

---

## 4. Kiểm Thử Nhanh (Dùng file `requests.http`)

Mở file [`requests.http`](file:///E:/Dev/JAVA/ex_ss12_micro/requests.http) trong IntelliJ IDEA hoặc VS Code để click **"Send Request"**:

1. **Test Redis Cache**:
   - Gửi `GET /api/v1/products/1` lần 1: Response time `~200ms`, `X-Data-Source: DATABASE (Cache Miss)`.
   - Gửi `GET /api/v1/products/1` lần 2: Response time `< 10ms`, `X-Data-Source: REDIS_CACHE (Cache Hit)`.
   - Gửi `PUT /api/v1/products/1`: Cache Redis bị xóa tự động qua `@CacheEvict`.

2. **Test Kafka Luồng Đặt Hàng**:
   - Gửi `GET /api/v1/inventory` kiểm tra tồn kho ban đầu (Sản phẩm 1: 50 chiếc, Sản phẩm 2: 40 chiếc).
   - Gửi `POST /api/v1/orders` đặt mua 2 chiếc SP1 và 1 chiếc SP2: Phản hồi ngay lập tức `201 Created`.
   - Quan sát console log của `email-service`: Nhận event và in log gửi email thành công.
   - Quan sát console log của `inventory-service`: Nhận event và trừ tồn kho SP1 còn 48 chiếc, SP2 còn 39 chiếc.
   - Gửi lại `GET /api/v1/inventory` để kiểm chứng số lượng tồn kho đã được cập nhật chính xác!

---

## 5. Chạy Toàn Bộ Test Suite Tự Động
```bash
.\gradlew.bat test
```
Tất cả các unit & integration test của cả 4 services đều sử dụng in-memory database và `@EmbeddedKafka`, đảm bảo kiểm tra tự động và pass 100%.
