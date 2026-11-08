package com.example.productservice.service.impl;

import com.example.productservice.config.RedisConfig;
import com.example.productservice.dto.request.ProductRequestDTO;
import com.example.productservice.dto.response.ProductResponseDTO;
import com.example.productservice.entity.Product;
import com.example.productservice.exception.OutOfStockException;
import com.example.productservice.exception.ResourceNotFoundException;
import com.example.productservice.repository.ProductRepository;
import com.example.productservice.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final RedissonClient redissonClient;

    /**
     * Lấy chi tiết sản phẩm theo ID áp dụng Cache-Aside pattern.
     * - Cache Hit: Lấy trực tiếp từ Redis (< 10ms), method này không được thực thi.
     * - Cache Miss: Gọi xuống Database (~200ms), kết quả được lưu tự động vào Redis với key là productId.
     */
    @Override
    @Cacheable(value = RedisConfig.PRODUCT_CACHE, key = "#id")
    @Transactional(readOnly = true)
    public ProductResponseDTO getProductById(Long id) {
        log.info(">>> [DATABASE QUERY] Cache Miss! Truy vấn SQL Database cho Product ID: {}", id);
        
        // Mô phỏng độ trễ truy vấn Database phức tạp (~200ms) theo yêu cầu bài tập
        simulateDatabaseLatency(200);

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm với ID: " + id));

        log.info(">>> [DATABASE QUERY] Lấy thành công dữ liệu từ DB cho ID: {}. Chuẩn bị lưu vào Redis Cache.", id);
        return mapToResponse(product);
    }

    @Override
    @Transactional
    public ProductResponseDTO createProduct(ProductRequestDTO requestDTO) {
        Product product = Product.builder()
                .name(requestDTO.getName())
                .description(requestDTO.getDescription())
                .imageUrl(requestDTO.getImageUrl())
                .price(requestDTO.getPrice())
                .stockQuantity(requestDTO.getStockQuantity())
                .build();

        Product saved = productRepository.save(product);
        log.info("Đã tạo mới sản phẩm ID: {}", saved.getId());
        return mapToResponse(saved);
    }

    /**
     * Cập nhật thông tin sản phẩm và tự động xóa Cache (@CacheEvict)
     * Đảm bảo dữ liệu trong Redis luôn đồng nhất khi quản trị viên cập nhật.
     */
    @Override
    @CacheEvict(value = RedisConfig.PRODUCT_CACHE, key = "#id")
    @Transactional
    public ProductResponseDTO updateProduct(Long id, ProductRequestDTO requestDTO) {
        log.info(">>> [CACHE EVICT] Bắt đầu cập nhật sản phẩm ID: {} và xóa cache tương ứng", id);

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm với ID: " + id));

        product.setName(requestDTO.getName());
        product.setDescription(requestDTO.getDescription());
        product.setImageUrl(requestDTO.getImageUrl());
        product.setPrice(requestDTO.getPrice());
        product.setStockQuantity(requestDTO.getStockQuantity());

        Product updated = productRepository.save(product);
        log.info(">>> [CACHE EVICT] Cập nhật thành công DB và đã xóa cache Redis cho ID: {}", id);
        return mapToResponse(updated);
    }

    /**
     * Mua sản phẩm trong đợt Flash Sale sử dụng Khóa phân tán Redisson.
     * - Key khóa: lock:product:{id}
     * - Khi khách hàng đặt mua, Service phải giành được khóa mới được phép kiểm tra tồn kho và trừ số lượng.
     * - Đảm bảo giải phóng khóa (Unlock) trong khối finally để tránh treo hệ thống.
     * - Khi hết hàng, ném ra OutOfStockException với thông báo: "Sản phẩm đã hết hàng"
     */
    @Override
    @CacheEvict(value = RedisConfig.PRODUCT_CACHE, key = "#productId")
    public ProductResponseDTO purchaseProductFlashSale(Long productId, Integer quantity) {
        int buyQty = (quantity != null && quantity > 0) ? quantity : 1;
        String lockKey = "lock:product:" + productId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean isLocked = false;

        try {
            // Giành khóa phân tán (chờ tối đa 5 giây, giữ khóa tối đa 10 giây tự giải phóng nếu crash)
            isLocked = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!isLocked) {
                log.warn(">>> [REDISSON LOCK] Thread [{}] không thể giành được khóa [{}] do timeout!",
                        Thread.currentThread().getName(), lockKey);
                throw new RuntimeException("Hệ thống đang quá tải, vui lòng thử lại sau!");
            }

            log.info(">>> [REDISSON LOCK] Thread [{}] đã giành được khóa [{}] thành công",
                    Thread.currentThread().getName(), lockKey);

            // 1. Kiểm tra thông tin sản phẩm và số lượng tồn kho
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm với ID: " + productId));

            if (product.getStockQuantity() < buyQty) {
                log.warn(">>> [HẾT HÀNG] Sản phẩm [{}] chỉ còn tồn {}, không đủ cho đơn hàng {}",
                        product.getName(), product.getStockQuantity(), buyQty);
                throw new OutOfStockException("Sản phẩm đã hết hàng");
            }

            // 2. Trừ tồn kho
            int remaining = product.getStockQuantity() - buyQty;
            product.setStockQuantity(remaining);
            Product updatedProduct = productRepository.save(product);

            log.info(">>> [ĐẶT MUA THÀNH CÔNG] Đã trừ kho sản phẩm [{}] ID {}: tồn cũ = {}, đã mua = {}, tồn mới = {}",
                    product.getName(), productId, product.getStockQuantity() + buyQty, buyQty, remaining);

            return mapToResponse(updatedProduct);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Quá trình đặt mua Flash Sale bị gián đoạn", e);
        } finally {
            // Đảm bảo giải phóng khóa (Unlock) trong khối finally để tránh treo hệ thống
            if (isLocked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info(">>> [REDISSON UNLOCK] Thread [{}] đã giải phóng khóa [{}]",
                        Thread.currentThread().getName(), lockKey);
            }
        }
    }

    /**
     * Xóa sản phẩm và xóa Cache tương ứng trong Redis
     */
    @Override
    @CacheEvict(value = RedisConfig.PRODUCT_CACHE, key = "#id")
    @Transactional
    public void deleteProduct(Long id) {
        log.info(">>> [CACHE EVICT] Xóa sản phẩm ID: {} và xóa cache tương ứng", id);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm với ID: " + id));
        productRepository.delete(product);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductResponseDTO> getAllProducts() {
        return productRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private ProductResponseDTO mapToResponse(Product product) {
        return ProductResponseDTO.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .imageUrl(product.getImageUrl())
                .price(product.getPrice())
                .stockQuantity(product.getStockQuantity())
                .build();
    }

    private void simulateDatabaseLatency(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

