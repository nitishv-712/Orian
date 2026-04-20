import 'package:flutter/material.dart';

class OutputChip extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool selected;
  final bool available;
  final ValueChanged<bool> onChanged;

  const OutputChip({
    super.key,
    required this.icon,
    required this.label,
    required this.selected,
    this.available = true,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: available ? 1.0 : 0.5,
      child: FilterChip(
        avatar: Icon(icon, size: 18),
        label: Text(label, style: const TextStyle(fontSize: 12)),
        selected: selected,
        onSelected: available ? onChanged : null,
        backgroundColor: Colors.grey[900],
        selectedColor: Colors.deepPurpleAccent.withAlpha(150),
        labelStyle: TextStyle(
          color: selected ? Colors.white : Colors.grey,
          fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
        ),
      ),
    );
  }
}
