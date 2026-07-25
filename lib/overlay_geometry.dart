import 'dart:math' as math;
import 'dart:ui' show Size;

import 'package:flutter_overlay_window/flutter_overlay_window.dart';

/// Geometria da faixa do teleprompter a partir do tamanho lógico da tela.
///
/// Mesma lógica usada na abertura (main.dart) e na adaptação em tempo real
/// quando o celular gira com o overlay já aberto (overlay_teleprompter.dart),
/// pra manter os dois sempre em sincronia.
class OverlayGeometry {
  const OverlayGeometry({required this.height, required this.position});

  final int height;
  final OverlayPosition position;

  static const int width = WindowSize.matchParent;

  factory OverlayGeometry.forScreen(Size logicalSize) {
    final landscape = logicalSize.width > logicalSize.height;
    if (landscape) {
      // Deitado, gravando em 16:9: a lente frontal sai do topo e vai pra
      // borda curta da tela. Perto da lente deixa de ser "perto do topo" —
      // o ponto discreto agora é o centro vertical, na altura dos olhos.
      final height = math.min(340.0, logicalSize.height * 0.55).round();
      return OverlayGeometry(
        height: height,
        position: const OverlayPosition(0, 0),
      );
    }
    // Em pé: faixa um pouco acima do centro, perto da lente frontal no topo.
    return const OverlayGeometry(
      height: 520,
      position: OverlayPosition(0, -160),
    );
  }
}
