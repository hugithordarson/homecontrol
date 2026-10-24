import SwiftUI
import UIKit

/// The color a light is showing, for icons and swatches
extension Light {
    var displayColor: Color {
        guard on && reachable else {
            return Color(.tertiaryLabel)
        }

        if colorMode, let hue, let saturation {
            return Color(hue: hue / 360, saturation: saturation, brightness: 1)
        }

        return kelvinColor(kelvin ?? 2700)
    }
}

/// Approximate blackbody color for a white temperature (Tanner Helland's fit)
func kelvinColor(_ kelvin: Int) -> Color {
    let t = Double(kelvin) / 100
    let red = t <= 66 ? 255 : 329.7 * pow(t - 60, -0.1332)
    let green = t <= 66 ? 99.47 * log(t) - 161.12 : 288.12 * pow(t - 60, -0.0755)
    let blue = t >= 66 ? 255 : (t <= 19 ? 0 : 138.52 * log(t - 10) - 305.04)
    let clamp = { (v: Double) in min(max(v, 0), 255) / 255 }
    return Color(red: clamp(red), green: clamp(green), blue: clamp(blue))
}

/// "#ff8800" → (hue in degrees, saturation 0–1), the way the hub wants a color
func hueSaturation(_ hex: String) -> (Double, Double)? {
    guard let color = uiColor(hex) else {
        return nil
    }

    var hue: CGFloat = 0, saturation: CGFloat = 0, brightness: CGFloat = 0, alpha: CGFloat = 0
    color.getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha)
    return (Double(hue) * 360, Double(saturation))
}

func uiColor(_ hex: String) -> UIColor? {
    let digits = hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))

    guard digits.count == 6, let value = Int(digits, radix: 16) else {
        return nil
    }

    return UIColor(red: CGFloat((value >> 16) & 0xff) / 255, green: CGFloat((value >> 8) & 0xff) / 255, blue: CGFloat(value & 0xff) / 255, alpha: 1)
}

extension Preset {
    /// The chip's swatch colors
    var colors: [Color] {
        if let kelvin {
            return [kelvinColor(kelvin)]
        }

        return (hexes ?? [hex].compactMap { $0 }).compactMap(uiColor).map(Color.init(uiColor:))
    }
}
