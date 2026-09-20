package com.lian.aicode.controller;

import com.lian.aicode.model.entity.App;
import com.lian.aicode.model.enums.AppDeploymentStatusEnum;
import com.lian.aicode.service.AppService;
import com.lian.aicode.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;

/** 受控提供应用预览和已部署静态资源，所有路径都经过规范化和根目录校验。 */
@RestController
public class StaticResourceController {

    private final AppService appService;
    private final UserService userService;

    public StaticResourceController(AppService appService, UserService userService) {
        this.appService = appService;
        this.userService = userService;
    }

    @GetMapping({"/preview/{appId}/{versionNo}", "/preview/{appId}/{versionNo}/",
            "/preview/{appId}/{versionNo}/**"})
    public ResponseEntity<Resource> preview(@PathVariable Long appId,
                                            @PathVariable Integer versionNo,
                                            HttpServletRequest request) {
        Path root = appService.getPreviewPath(appId, versionNo, optionalLoginUser(request));
        return serve(root, request, "/preview/" + appId + "/" + versionNo);
    }

    @GetMapping({"/site/{deployKey}", "/site/{deployKey}/", "/site/{deployKey}/**"})
    public ResponseEntity<Resource> deployed(@PathVariable String deployKey, HttpServletRequest request) {
        App app = appService.findByDeployKey(deployKey);
        if (app == null || !AppDeploymentStatusEnum.DEPLOYED.getValue().equals(app.getDeploymentStatus())) {
            return ResponseEntity.notFound().build();
        }
        return serve(appService.getDeployPath(deployKey), request, "/site/" + deployKey);
    }

    private ResponseEntity<Resource> serve(Path root, HttpServletRequest request, String prefix) {
        try {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            String pathWithin = (String) request.getAttribute(
                    HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            String resourcePart = pathWithin == null ? "" : pathWithin.substring(Math.min(prefix.length(), pathWithin.length()));
            if (resourcePart.isBlank() || "/".equals(resourcePart)) {
                resourcePart = "/index.html";
            }
            Path resourcePath = normalizedRoot.resolve(resourcePart.replaceFirst("^/", ""))
                    .normalize();
            if (!resourcePath.startsWith(normalizedRoot) || Files.isSymbolicLink(resourcePath)) {
                return ResponseEntity.notFound().build();
            }
            // 为后续可能生成的前端路由保留 index.html 回退；带扩展名的资源仍严格返回 404。
            if (!Files.isRegularFile(resourcePath) && !hasFileExtension(resourcePart)) {
                resourcePath = normalizedRoot.resolve("index.html").normalize();
            }
            if (!resourcePath.startsWith(normalizedRoot)
                    || Files.isSymbolicLink(resourcePath)
                    || !Files.isRegularFile(resourcePath)) {
                return ResponseEntity.notFound().build();
            }
            // 仅检查 normalize 不能防止“版本目录内的中间层符号链接”跳出根目录。
            // 生成器不会创建符号链接，但这里仍按真实路径再次收口，避免手工篡改文件造成越权读取。
            if (!isInsideRealRoot(normalizedRoot, resourcePath)) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new FileSystemResource(resourcePath);
            String contentType = contentType(resourcePath);
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_TYPE, contentType);
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline");
            headers.add("X-Content-Type-Options", "nosniff");
            headers.add("Referrer-Policy", "no-referrer");
            headers.add(HttpHeaders.CACHE_CONTROL, "no-cache");
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean hasFileExtension(String resourcePart) {
        String fileName = resourcePart.substring(resourcePart.lastIndexOf('/') + 1);
        return fileName.contains(".");
    }

    private boolean isInsideRealRoot(Path root, Path resource) {
        try {
            return resource.toRealPath().startsWith(root.toRealPath());
        } catch (java.io.IOException exception) {
            return false;
        }
    }

    private String contentType(Path resourcePath) {
        try {
            String detected = Files.probeContentType(resourcePath);
            if (detected != null) {
                return detected.startsWith("text/") && !detected.toLowerCase().contains("charset")
                        ? detected + "; charset=UTF-8" : detected;
            }
        } catch (Exception ignored) {
            // 根据扩展名继续回退，不能因为 Windows MIME 注册表缺失而影响预览。
        }
        String name = resourcePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (name.endsWith(".css")) return "text/css; charset=UTF-8";
        if (name.endsWith(".js")) return "text/javascript; charset=UTF-8";
        if (name.endsWith(".json")) return "application/json; charset=UTF-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }

    private com.lian.aicode.model.entity.UserAccount optionalLoginUser(HttpServletRequest request) {
        try {
            return userService.getLoginUser(request);
        } catch (com.lian.aicode.exception.BusinessException exception) {
            if (exception.getCode() == com.lian.aicode.exception.ErrorCode.NOT_LOGIN_ERROR.getCode()) {
                return null;
            }
            throw exception;
        }
    }
}
