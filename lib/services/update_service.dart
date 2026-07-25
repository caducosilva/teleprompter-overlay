import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

class UpdateService {
  static const _owner = 'caducosilva';
  static const _repo = 'teleprompter-overlay';

  static Future<void> checkForUpdate(BuildContext context) async {
    try {
      final pkgInfo = await PackageInfo.fromPlatform();
      final currentVersion = pkgInfo.version;

      final client = HttpClient()
        ..connectionTimeout = const Duration(seconds: 8);
      final request = await client.getUrl(
        Uri.parse(
            'https://api.github.com/repos/$_owner/$_repo/releases/latest'),
      );
      request.headers.set('User-Agent', 'PromptCue-App/$currentVersion');
      final response = await request.close();
      if (response.statusCode != 200) return;

      final body = await response.transform(const Utf8Decoder()).join();
      client.close(force: false);

      final json = jsonDecode(body) as Map<String, dynamic>;
      final latestTag =
          (json['tag_name'] as String? ?? '').replaceAll(RegExp(r'^v'), '');
      final htmlUrl = json['html_url'] as String? ?? '';

      if (!_isNewer(latestTag, currentVersion)) return;
      // Aviso no máximo 1x por dia por versão, repetindo todo dia até atualizar.
      if (await _alreadyPromptedToday(latestTag)) return;
      await _markPromptedToday(latestTag);
      if (!context.mounted) return;
      _showDialog(context, latestTag, htmlUrl);
    } catch (_) {
      // Silent fail — update check is non-critical
    }
  }

  /// Já avisamos hoje sobre esta versão?
  static Future<bool> _alreadyPromptedToday(String tag) async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString('update_prompt_date') == _todayKey() &&
        prefs.getString('update_prompt_tag') == tag;
  }

  static Future<void> _markPromptedToday(String tag) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('update_prompt_date', _todayKey());
    await prefs.setString('update_prompt_tag', tag);
  }

  static String _todayKey() {
    final n = DateTime.now();
    return '${n.year}-${n.month}-${n.day}';
  }

  static bool _isNewer(String latest, String current) {
    List<int> parse(String v) =>
        v.split('.').map((s) => int.tryParse(s) ?? 0).toList();
    final l = parse(latest);
    final c = parse(current);
    for (var i = 0; i < 3; i++) {
      final lv = i < l.length ? l[i] : 0;
      final cv = i < c.length ? c[i] : 0;
      if (lv > cv) return true;
      if (lv < cv) return false;
    }
    return false;
  }

  static void _showDialog(BuildContext context, String version, String url) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: const Icon(Icons.new_releases_outlined),
        title: Text('Versão $version disponível'),
        content: const Text(
          'Uma nova versão do PromptCue está disponível. Deseja baixar agora?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Agora não'),
          ),
          FilledButton.icon(
            icon: const Icon(Icons.download_rounded),
            label: const Text('Baixar'),
            onPressed: () {
              Navigator.pop(ctx);
              launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
            },
          ),
        ],
      ),
    );
  }
}
