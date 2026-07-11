<script setup lang="ts">
import { TabsList, TabsRoot, TabsTrigger } from 'reka-ui'
import { computed, type Component } from 'vue'

export interface Tab {
  label: string
  value: string
  icon?: Component
}

const props = withDefaults(
  defineProps<{
    tabs: readonly Tab[]
    modelValue?: string
    disabled?: boolean
    compact?: boolean
    ariaLabel?: string
  }>(),
  {
    modelValue: undefined,
    disabled: false,
    compact: false,
    ariaLabel: undefined,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const activeValue = computed({
  get: () => props.modelValue ?? props.tabs[0]?.value ?? '',
  set: (val) => emit('update:modelValue', val),
})
</script>

<template>
  <TabsRoot
    v-model="activeValue"
    activation-mode="manual"
    class=":uno: min-w-0 inline-flex"
    :class="props.compact ? ':uno: flex-none' : ':uno: w-full sm:w-auto'"
  >
    <TabsList
      :aria-label="props.ariaLabel"
      class=":uno: w-full inline-flex items-center justify-start rounded-lg sm:w-auto"
      :class="
        props.compact
          ? ':uno: h-9 border border-slate-200 bg-slate-100/80 !p-0.5'
          : ':uno: h-10 items-baseline bg-gray-200/50 p-1'
      "
    >
      <TabsTrigger
        v-for="item in props.tabs"
        :key="item.value"
        :value="item.value"
        :disabled="props.disabled"
        class=":uno: group min-w-[32px] w-full inline-flex items-center justify-center gap-1.5 whitespace-nowrap bg-transparent text-slate-600 outline-none transition-all sm:w-auto disabled:cursor-not-allowed data-[state=active]:bg-white data-[state=active]:text-slate-950 disabled:text-slate-400"
        :class="
          props.compact
            ? ':uno: h-7 rounded-md text-xs font-medium !px-3 hover:text-slate-800 data-[state=active]:shadow-sm data-[state=active]:ring-1 data-[state=active]:ring-slate-200'
            : ':uno: h-8 px-4 py-2 align-middle text-xs font-semibold duration-300 ease-in-out hover:text-blue-950 data-[state=active]:rounded-md data-[state=active]:drop-shadow'
        "
      >
        <component :is="item.icon" v-if="item.icon" class=":uno: size-3.5" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </TabsTrigger>
    </TabsList>
  </TabsRoot>
</template>
