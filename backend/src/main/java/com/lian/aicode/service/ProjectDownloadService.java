package com.lian.aicode.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/** 将受控代码版本安全打包为 ZIP。 */
public interface ProjectDownloadService {

    DownloadResult writeZip(Path projectDirectory, OutputStream outputStream) throws IOException;

    record DownloadResult(int fileCount) {
    }
}
