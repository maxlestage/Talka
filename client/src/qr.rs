//! Lire un QR code dans une image de la caméra, en Rust : pour les
//! navigateurs qui n'ont pas `BarcodeDetector` (Safari sur iPhone et iPad,
//! Firefox). Compilé aussi hors du navigateur, pour les tests.

/// Les textes des QR codes trouvés dans une image RGBA (`width` × `height`,
/// 4 octets par pixel, comme `ImageData`).
pub fn decode_rgba(width: usize, height: usize, rgba: &[u8]) -> Vec<String> {
    if width == 0 || height == 0 || rgba.len() < width * height * 4 {
        return Vec::new();
    }
    let mut img = rqrr::PreparedImage::prepare_from_greyscale(width, height, |x, y| {
        let i = (y * width + x) * 4;
        // Luminance (Rec. 601), sans flottants.
        ((rgba[i] as u32 * 299 + rgba[i + 1] as u32 * 587 + rgba[i + 2] as u32 * 114) / 1000) as u8
    });
    img.detect_grids()
        .into_iter()
        .filter_map(|g| g.decode().ok().map(|(_, text)| text))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Un QR code dessiné (comme celui de la liaison), puis relu.
    #[test]
    fn reads_what_talka_draws() {
        let text = "talka-lien:AQAAAAAAAADDBwcHBwcHBwcHBwcHBwcH";
        let code = qrcode::QrCode::with_error_correction_level(text, qrcode::EcLevel::M).unwrap();
        let modules = code.width();
        let scale = 6;
        let quiet = 4;
        let size = (modules + 2 * quiet) * scale;
        let mut rgba = vec![255u8; size * size * 4];
        let colors = code.to_colors();
        for y in 0..modules {
            for x in 0..modules {
                if colors[y * modules + x] == qrcode::Color::Dark {
                    for dy in 0..scale {
                        for dx in 0..scale {
                            let px = (x + quiet) * scale + dx;
                            let py = (y + quiet) * scale + dy;
                            let i = (py * size + px) * 4;
                            rgba[i..i + 3].copy_from_slice(&[11, 20, 26]);
                        }
                    }
                }
            }
        }
        assert_eq!(decode_rgba(size, size, &rgba), vec![text.to_owned()]);
        // Une image vide : rien.
        assert!(decode_rgba(size, size, &vec![255u8; size * size * 4]).is_empty());
        assert!(decode_rgba(0, 0, &[]).is_empty());
    }
}
