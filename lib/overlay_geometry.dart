import 'dart:ui' show Size;

import 'package:flutter/widgets.dart' show EdgeInsets;
import 'package:flutter_overlay_window/flutter_overlay_window.dart';

/// Geometria da faixa do teleprompter: largura e altura sempre fixas
/// (o usuário só arrasta a posição), mas a posição inicial é calculada
/// pra ficar na altura dos olhos de quem grava olhando pra lente frontal.
///
/// A maioria dos celulares Samsung Ultra (incluindo o S25 Ultra) tem a
/// câmera frontal num furo centralizado no topo da tela. Em vez de
/// cravar um valor de pesquisa (que muda de aparelho pra aparelho e é
/// pouco confiável), a posição é calculada a partir da folga real que o
/// Android reserva pro furo da câmera/barra de status
/// (`MediaQuery.of(context).padding`), medida uma única vez na tela
/// principal do app (a Activity normal, que sabe a orientação de forma
/// confiável — ao contrário da janela de overlay).
class OverlayGeometry {
  const OverlayGeometry({required this.height, required this.position});

  final int height;
  final OverlayPosition position;

  static const int width = 320;
  static const int _height = 300;

  /// Espaço extra além da folga da câmera/barra de status, pra sobrar
  /// espaço de arrastar sem entrar na área sensível do sistema.
  static const double _margin = 20;

  static const OverlayGeometry standard = OverlayGeometry(
    height: _height,
    position: OverlayPosition(0, 0),
  );

  /// [screenSize] e [safePadding] vêm de `MediaQuery.of(context)` na tela
  /// principal do app, no momento de abrir o overlay.
  factory OverlayGeometry.forEyeLevel({
    required Size screenSize,
    required EdgeInsets safePadding,
  }) {
    final landscape = screenSize.width > screenSize.height;

    if (!landscape) {
      // Retrato: a lente fica centralizada no topo. Fica logo abaixo da
      // folga que o sistema já reserva pra ela (padding.top).
      final topEdge = safePadding.top + _margin;
      final centerY = topEdge + _height / 2;
      final offsetY = centerY - screenSize.height / 2;
      return OverlayGeometry(
        height: _height,
        position: OverlayPosition(0, offsetY),
      );
    }

    // Paisagem: girando o aparelho, a lente sai do topo e vai pra borda
    // curta esquerda ou direita — depende de qual lado o usuário girou.
    // padding.left/right aponta qual foi, medido nesse instante.
    final onLeft = safePadding.left >= safePadding.right;
    final sideInset = onLeft ? safePadding.left : safePadding.right;
    final edge = sideInset + _margin;
    final centerX = edge + width / 2;
    final rawOffsetX = centerX - screenSize.width / 2;
    final offsetX = onLeft ? rawOffsetX : -rawOffsetX;
    return OverlayGeometry(
      height: _height,
      position: OverlayPosition(offsetX, 0),
    );
  }
}
