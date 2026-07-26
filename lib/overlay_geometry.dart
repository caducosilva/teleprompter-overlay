import 'package:flutter_overlay_window/flutter_overlay_window.dart';

/// Geometria fixa da faixa do teleprompter: largura e altura sempre iguais,
/// só a posição muda (o usuário arrasta). Serve tanto pra gravar em 9:16
/// (retrato) quanto em 16:9 (paisagem) sem precisar detectar orientação —
/// não dá pra ler isso de forma confiável de dentro de uma janela de
/// overlay (janela de sistema, não a activity normal do app).
class OverlayGeometry {
  const OverlayGeometry({required this.height, required this.position});

  final int height;
  final OverlayPosition position;

  static const int width = 320;

  static const OverlayGeometry standard = OverlayGeometry(
    height: 300,
    position: OverlayPosition(0, 0),
  );
}
