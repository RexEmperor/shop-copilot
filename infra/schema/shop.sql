CREATE DATABASE IF NOT EXISTS shop CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shop;

CREATE TABLE IF NOT EXISTS product (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商品主键ID',
  name VARCHAR(128) NOT NULL COMMENT '商品名称',
  category VARCHAR(64) NOT NULL COMMENT '商品分类',
  description TEXT NOT NULL COMMENT '商品描述',
  price_cents BIGINT NOT NULL COMMENT '商品价格，单位分',
  stock INT NOT NULL DEFAULT 0 COMMENT '可售库存',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '商品状态：0-下架 1-上架',
  version INT NOT NULL DEFAULT 0 COMMENT '版本号，用于乐观锁',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_category_status (category, status),
  CONSTRAINT chk_product_price CHECK (price_cents >= 0),
  CONSTRAINT chk_product_stock CHECK (stock >= 0),
  CONSTRAINT chk_product_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品表';

CREATE TABLE IF NOT EXISTS orders (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '订单主键ID',
  order_no VARCHAR(32) NOT NULL COMMENT '业务订单号',
  user_id BIGINT NOT NULL DEFAULT 10001 COMMENT '用户ID，一期固定为10001',
  product_id BIGINT NOT NULL COMMENT '商品ID，逻辑关联product.id',
  product_name_snapshot VARCHAR(128) NOT NULL COMMENT '下单时商品名称快照',
  unit_price_cents BIGINT NOT NULL COMMENT '下单时商品单价，单位分',
  quantity INT NOT NULL COMMENT '购买数量',
  total_amount_cents BIGINT NOT NULL COMMENT '订单总金额，单位分',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '订单状态：0-待支付 1-已支付 2-已发货 3-已完成 4-已取消',
  version INT NOT NULL DEFAULT 0 COMMENT '版本号，用于乐观锁',
  receiver_name VARCHAR(64) NOT NULL COMMENT '收货人姓名',
  receiver_phone VARCHAR(32) NOT NULL COMMENT '收货人手机号',
  shipping_address VARCHAR(255) NOT NULL COMMENT '收货地址',
  paid_at DATETIME DEFAULT NULL COMMENT '支付时间',
  shipped_at DATETIME DEFAULT NULL COMMENT '发货时间',
  delivered_at DATETIME DEFAULT NULL COMMENT '确认收货时间',
  cancelled_at DATETIME DEFAULT NULL COMMENT '取消时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  KEY idx_user_status (user_id, status),
  KEY idx_product_id (product_id),
  CONSTRAINT chk_order_unit_price CHECK (unit_price_cents >= 0),
  CONSTRAINT chk_order_quantity CHECK (quantity > 0),
  CONSTRAINT chk_order_total CHECK (total_amount_cents >= 0),
  CONSTRAINT chk_order_status CHECK (status IN (0, 1, 2, 3, 4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表，一期按一单一件商品建模';

CREATE TABLE IF NOT EXISTS aftersale_ticket (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '售后工单主键ID',
  ticket_no VARCHAR(32) NOT NULL COMMENT '业务售后单号',
  order_no VARCHAR(32) NOT NULL COMMENT '关联订单号，逻辑关联orders.order_no',
  user_id BIGINT NOT NULL DEFAULT 10001 COMMENT '用户ID，一期固定为10001',
  type TINYINT NOT NULL COMMENT '售后类型：0-仅退款 1-退货退款',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '售后状态：0-待审核 1-退款中 2-已退款 3-已拒绝',
  refund_cents BIGINT NOT NULL COMMENT '退款金额，单位分，由订单金额派生',
  reason VARCHAR(500) NOT NULL COMMENT '售后原因',
  review_remark VARCHAR(500) DEFAULT NULL COMMENT '审核备注',
  reviewed_at DATETIME DEFAULT NULL COMMENT '审核通过或拒绝时间',
  refunded_at DATETIME DEFAULT NULL COMMENT '退款完成时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ticket_no (ticket_no),
  KEY idx_order_no_status (order_no, status),
  CONSTRAINT chk_aftersale_refund CHECK (refund_cents >= 0),
  CONSTRAINT chk_aftersale_type CHECK (type IN (0, 1)),
  CONSTRAINT chk_aftersale_status CHECK (status IN (0, 1, 2, 3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='售后工单表';
