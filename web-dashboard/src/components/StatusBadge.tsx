interface StatusBadgeProps {
  active: boolean;
  activeText?: string;
  inactiveText?: string;
}

export default function StatusBadge({
  active,
  activeText = 'Active',
  inactiveText = 'Inactive',
}: StatusBadgeProps) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${
        active
          ? 'bg-green-50 text-green-700 ring-1 ring-inset ring-green-600/20'
          : 'bg-red-50 text-red-700 ring-1 ring-inset ring-red-600/20'
      }`}
    >
      {active ? activeText : inactiveText}
    </span>
  );
}
