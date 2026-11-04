package ru.partitrainer.omr.homr

import java.io.File
import javax.imageio.ImageIO

/** Загрузка PNG/JPEG как серой картинки (для тестов). */
object TestImages {
    fun loadGray(f: File): GrayImage {
        val img = ImageIO.read(f)
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return GrayImage.fromArgb(img.width, img.height, px)
    }
}
