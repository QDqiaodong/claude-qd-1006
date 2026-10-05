const STORAGE_KEY = 'waterworks.dutyOperator'

export function getDutyOperator() {
  return localStorage.getItem(STORAGE_KEY) || ''
}

export function setDutyOperator(name) {
  localStorage.setItem(STORAGE_KEY, name || '')
}
