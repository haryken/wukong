Thư mục này chứa thư viện Sauron (camera TakePicApi = com.ubtechinc.sauron.api).

Đã tạo sẵn (từ repo local của bạn):
  sauron-api-from-mini-outer-no-proguard.jar
    — trích toàn bộ package com/ubtechinc/sauron/* từ
      mini-outer-sdk-demo/app/libs/mini-outer-sdk-no-proguard.jar
    — để KHÔNG trùng class với mini-outer-sdk-lite.jar + behavior-lib trong libs/.

Nếu ROM báo thiếu class khác (NoClassDefFoundError) khi gọi TakePicApi, khi đó thử thay bằng bản đầy đủ:
  xóa file slim ở trên, copy nguyên mini-outer-sdk-no-proguard.jar vào đây VÀ
  xóa mini-outer-sdk-lite.jar ở libs/ (chỉ giữ một bản outer SDK) — cần chỉnh dependency
  trùng behavior-lib (xem lịch sử build duplicate).

Sau khi thêm/sửa file: Sync Gradle, Rebuild, cài lại APK. Log khởi động:
  "Sauron TakePicApi: có trong APK" = OK.

Nếu log/MCP báo 404 Call NOT found cho /api/camera/take_picture_Immediately và take_a_picture:
  đó là giới hạn ROM — Master không có service camera Sauron. App không sửa được bằng code;
  cần firmware đúng bản hoặc thiết bị có route camera từ nhà máy.

speechFrameworkDemo: khi gặp lỗi trên, MCP vision sẽ thử fallback Camera2 (ImageReader JPEG)
  nếu đã cấp quyền android.permission.CAMERA (MainActivity xin khi mở app).
