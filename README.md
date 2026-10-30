# AutoClick – Auto clicker + nhấn giữ + dán chữ + kéo thả (Kotlin, Android)

App auto clicker nhiều điểm giống các app trên CH Play, có thêm điểm **"nhấn giữ"**, **"click rồi dán chữ"** và **"kéo thả"**.
Không cần root, không cần quyền "Hiển thị trên ứng dụng khác" – chỉ cần cấp quyền **Trợ năng (Accessibility)** một lần.

## Yêu cầu
- Android Studio **Narwhal 3 Feature Drop (2025.1.3)** trở lên (project dùng Android Gradle Plugin 8.13.0).
- Điện thoại **Android 7.0** trở lên (nhấn giữ trước khi kéo cần Android 8.0+).

## Mở project và cài lên điện thoại
1. Giải nén → Android Studio → **File → Open** → chọn thư mục `AutoClickPaste`.
2. Chờ **Gradle Sync** chạy xong (lần đầu tải Gradle 8.14.3 + Android Gradle Plugin 8.13.0, hơi lâu).
3. Trên điện thoại bật **Tùy chọn nhà phát triển → Gỡ lỗi USB**, cắm cáp, bấm **Run ▶**.
   Muốn lấy file APK: **Build → Generate App Bundles or APKs → Generate APKs** (file ở `app/build/outputs/apk/debug/app-debug.apk`). Mở file APK từ Zalo / trình duyệt / trình quản lý tệp có thể bị Play Protect chặn – xem *Lỗi thường gặp*.

## Đổi tên app
Sửa 2 dòng trong `app/src/main/res/values/strings.xml` rồi build lại:
- `app_name` – tên dưới biểu tượng và tiêu đề trong app.
- `service_name` – tên trong **Cài đặt → Trợ năng**.

Các chỗ khác trong app tự lấy tên mới. Không cần đổi mã gói `com.example.autoclickpaste`.

## Màn hình chính
- **AUTO CLICK** – trạng thái (chấm xanh = đang bật) và nút lớn **BẬT / TẮT**.
  Lần đầu bấm BẬT, app mở Cài đặt Trợ năng → chọn **AutoClick** → chỉ bật **Sử dụng AutoClick** → **Cho phép** (không cần bật "Phím tắt"). Chỉ làm 1 lần, sau đó bật / tắt ngay trong app.
- **CẤU HÌNH ĐÃ LƯU** – danh sách cấu hình: **Mở** để dùng, **⋮** để Đổi tên / Xóa, **＋ Lưu cấu hình hiện tại** để lưu bộ điểm đang đặt.
- **CÀI ĐẶT → Giao diện** – kích thước điểm, kích cỡ / hướng bảng điều khiển, chữ dưới biểu tượng, độ đậm (có xem trước, áp dụng ngay).
- Khung cảnh báo màu cam chỉ hiện khi *phím tắt Trợ năng* của Android đang bật cho app (nút tròn ở mép màn hình) – bấm **Tắt nút tròn** để gỡ.

## Bảng điều khiển nổi

| Nút | Chức năng |
|---|---|
| ▶ Chạy / ■ Dừng | Chạy lần lượt điểm 1 → 2 → 3… rồi lặp lại |
| + Thêm | Thêm một điểm click (vòng tròn **xanh dương**) |
| − Xóa | Xóa điểm cuối cùng |
| ⚙ Cài đặt | Số vòng lặp (0 = vô hạn), thời gian chờ mặc định, lưu / mở cấu hình nhanh, xóa tất cả |
| ▼ Thu nhỏ | Cất bảng và các điểm, chỉ còn 1 nút tròn nhỏ (kéo được); chạm nút tròn để mở lại |

Kéo ở **bất kỳ chỗ nào** trên bảng (kể cả đè lên nút) để di chuyển bảng; chạm nhẹ thì là bấm nút.

## Các loại điểm
Kéo vòng tròn tới đúng chỗ cần thao tác – **chấm ở tâm** là đúng chỗ app sẽ bấm, số thứ tự nằm ở góc vòng tròn. **Chạm nhẹ** vào vòng tròn để chọn kiểu (Click / Giữ / Dán / Kéo), đổi thời gian chờ sau bước đó, hoặc xóa điểm:

| Loại | Màu | Làm gì |
|---|---|---|
| Chỉ click | Xanh dương | Chạm 1 lần vào tâm vòng tròn |
| Nhấn giữ | Tím | Giữ yên ngón tay tại tâm vòng tròn trong *thời gian nhấn giữ* (mặc định 1000 ms, tối đa 59 giây) rồi nhả |
| Click rồi dán chữ | Cam | Chạm vào ô nhập rồi dán nội dung bạn nhập sẵn |
| Kéo thả | Xanh lá | Có thêm vòng tròn **"thả"** và mũi tên. Kéo từ vòng "kéo" tới vòng "thả" |

- **Chờ sau bước này**: thời gian nghỉ sau khi thao tác xong, trước khi sang điểm tiếp theo.
- **Dán chữ**: mặc định dán tại vị trí con trỏ như bấm "Dán"; app không hỗ trợ thì tự ghi thẳng chữ vào ô. Tick **"Xóa chữ cũ trong ô rồi mới dán"** để ghi đè cả ô.
- **Kéo thả**: *Thời gian kéo* (300 ms = vuốt nhanh, 1000 ms = kéo chậm) và *Nhấn giữ trước khi kéo* (0 = vuốt ngay; kéo biểu tượng / đồ vật trong game thường cần 500–1000 ms, Android 8.0+).

## Cấu hình
Một cấu hình lưu toàn bộ điểm (vị trí, loại, thời gian chờ, nội dung dán, kéo thả, nhấn giữ) kèm số vòng lặp và thời gian chờ mặc định.
- Lưu: trong app bấm **＋ Lưu cấu hình hiện tại**, hoặc trên bảng nổi bấm **⚙ → 💾 Lưu cấu hình…**. Trùng tên cấu hình khác thì app hỏi trước khi ghi đè; lưu lại đúng cấu hình đang mở thì ghi đè luôn.
- Mở: bấm **Mở** trong app, hoặc **⚙ → 📂 Mở cấu hình đã lưu** trên bảng nổi. Các điểm đang đặt được thay bằng cấu hình đó.
- Các điểm đang đặt vẫn được tự lưu, kể cả khi chưa đặt tên cấu hình.

## Cấu trúc code
| File | Nội dung |
|---|---|
| `ClickService.kt` | Dịch vụ Trợ năng: bảng nổi, các điểm, chạy/dừng, click / nhấn giữ / kéo thả (`dispatchGesture`), dán chữ, hộp thoại |
| `MainActivity.kt` | Màn hình chính: BẬT/TẮT, cấu hình đã lưu, mở Giao diện |
| `AppearanceActivity.kt` | Màn hình Giao diện: kích thước điểm, kích cỡ / hướng bảng, chữ dưới nút, độ đậm |
| `ProfileActions.kt` | Lưu / mở / đổi tên / xóa cấu hình từ màn hình chính (đồng bộ với dịch vụ) |
| `Model.kt` | Dữ liệu điểm, cấu hình, giao diện (SharedPreferences, JSON) |
| `Ui.kt` | Biểu tượng vẽ bằng code, nút của bảng điều khiển, vòng tròn điểm (dùng chung cho bảng nổi và phần xem trước) |
| `AppUi.kt` | Bộ giao diện của app: thanh tiêu đề, thẻ, nút, dòng cài đặt |
| `OverlayUi.kt` | Hộp thoại nổi dạng thẻ (sửa điểm, cài đặt, cấu hình): ô nhập, công tắc, nút chọn kiểu |
| `res/xml/accessibility_service_config.xml` | Khai báo quyền thao tác cử chỉ và đọc ô nhập |

## Lỗi thường gặp
- **Mở file APK bị Play Protect báo "Đã chặn ứng dụng để bảo vệ thiết bị của bạn"** (không có nút Vẫn cài đặt): Google tự chặn APK dùng quyền Trợ năng khi cài từ trình duyệt, ứng dụng nhắn tin hoặc trình quản lý tệp. Cài qua cáp USB bằng Android Studio (**Run ▶**), hoặc tạm dừng Play Protect: CH Play → ảnh đại diện → **Play Protect** → ⚙ → tắt **Quét ứng dụng bằng Play Protect** → **Tạm dừng**, rồi mở lại file APK.
- **Có nút tròn mang biểu tượng app ở mép màn hình:** đó là *phím tắt Trợ năng* của Android, app không cần nó. Trong app bấm **Tắt nút tròn** → chọn AutoClick → tắt **Phím tắt AutoClick**.
- **Sync báo "incompatible version … of the Android Gradle plugin":** sửa số phiên bản `com.android.application` trong file `build.gradle.kts` ở thư mục gốc thành đúng bản "Latest supported" mà thông báo lỗi ghi, rồi Sync lại.
- **Không cấp được quyền Trợ năng ("cài đặt bị hạn chế")** – Android 13+ khi cài APK từ ngoài: Cài đặt → Ứng dụng → AutoClick → menu ⋮ → **Cho phép cài đặt bị hạn chế**, rồi bật lại. (Cài trực tiếp từ Android Studio thường không bị.)
- **Dịch vụ tự tắt** (Xiaomi, Oppo, Vivo…): cho app *Tự khởi động* và tắt *Tối ưu pin* cho app.
- **Bàn phím đẩy nút Gửi lên cao:** đặt vòng tròn nút Gửi lúc bàn phím đang hiện.
- Một số app (ngân hàng, game chống bot) chặn thao tác tự động hoặc chặn dán.
