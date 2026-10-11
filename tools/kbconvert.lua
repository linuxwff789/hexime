#!/usr/bin/env lua5.4
-- kbconvert.lua — 把小鹤主题(trime2)的键盘 lua 转成 hexime 的键盘 JSON。
--
-- 用法: lua5.4 tools/kbconvert.lua [src_dir] [out_dir]
--   默认 src = /sdcard/Documents/Rime/themes/小鹤/keyboards
--   默认 out = ime/src/main/assets/keyboards
--
-- 输出 schema（Kotlin 侧 ui/KeyboardData.kt 消费）:
--   { id, name, keyWidth, ascii, lock,
--     rows: [ { h: <行高百分比>, keys: [ {w,label,hint,style,click,long,up,down,left,right,ascii} ] } ] }
--   action = { k = "sym|key|ctrl|text|kb|toggle|cand|cmd|none", v = ..., opt = ..., mask = ... }
--
-- 转换规则: trime2 的 preset/send 名字在转换期就归一化成上面的 k，Kotlin 只做 dispatch。

local SRC = arg[1] or "/sdcard/Documents/Rime/themes/小鹤/keyboards"
local OUT = arg[2] or "ime/src/main/assets/keyboards"

---------------------------------------------------------------- json

local function esc(s)
  s = s:gsub("\\", "\\\\"):gsub('"', '\\"'):gsub("\n", "\\n"):gsub("\r", "\\r"):gsub("\t", "\\t")
  return s
end

local function jval(v)
  local t = type(v)
  if t == "nil" then return "null" end
  if t == "boolean" then return tostring(v) end
  if t == "number" then
    if v == math.floor(v) and math.abs(v) < 1e15 then return string.format("%d", v) end
    return string.format("%.4f", v):gsub("0+$", ""):gsub("%.$", "")
  end
  if t == "string" then return '"' .. esc(v) .. '"' end
  if t == "table" then
    -- 数组 or 对象
    local isArray = #v > 0
    local out = {}
    if isArray then
      for _, x in ipairs(v) do out[#out + 1] = jval(x) end
      return "[" .. table.concat(out, ",") .. "]"
    end
    local keys = {}
    for k in pairs(v) do keys[#keys + 1] = k end
    table.sort(keys)
    for _, k in ipairs(keys) do out[#out + 1] = '"' .. esc(k) .. '":' .. jval(v[k]) end
    return "{" .. table.concat(out, ",") .. "}"
  end
  return "null"
end

---------------------------------------------------------------- action 归一化

-- trime2 的具名功能键：原样透传给引擎/服务端按 X11 keysym 或专用手势处理
local NAMED = {
  BackSpace = true, space = true, space1 = true, Return = true, Tab = true, Escape = true,
  Delete = true, Up = true, Down = true, Left = true, Right = true,
  Page_Up = true, Page_Down = true, Home = true, End = true,
  Shift_L = true, Shift_R = true,
  -- 编辑类辅助键（服务端用 InputConnection / KeyEvent 实现）
  undo = true, redo = true, copy = true, paste = true, cut = true, select_all = true,
  -- 标识性名字，服务端会做特殊处理
  Space = true,
}

local function cmd_of(command, option)
  if not command then return { k = "none" } end
  local base = command:match("([^/]+)$") or command
  if base == "settings.lua" then return { k = "cmd", v = "settings" } end
  if base == "keyboard_height.lua" then return { k = "cmd", v = "kbheight", opt = option } end
  if base == "addword.lua" then return { k = "cmd", v = "addword", opt = option } end
  if base == "set_kb.lua" and option then return { k = "kb", v = option } end
  if base == "date" then return { k = "cmd", v = "date", opt = option } end
  if base == "run" then return { k = "cmd", v = "run", opt = option } end
  if base == "web_search" then return { k = "cmd", v = "web", opt = option } end
  if base == "broadcast" then return { k = "cmd", v = "deploy" } end
  return { k = "none" }
end

local function to_action(c)
  if c == nil then return nil end
  local t = type(c)

  if t == "number" then return { k = "sym", v = tostring(c) } end

  if t == "string" then
    local mod, rest = c:match("^(%a+)%+(.+)$")
    if mod == "Control" then return { k = "ctrl", v = rest } end
    if mod == "Shift" then return { k = "sym", v = rest, mask = "shift" } end
    if #c == 1 then return { k = "sym", v = c } end
    -- 旧 preset 名 → hexime 键盘 id / 动作
    if c == "Keyboard_default" then return { k = "kb", v = "default" } end
    if c == "Keyboard_symbols" then return { k = "kb", v = "symbols" } end
    if c == "Keyboard_number" then return { k = "kb", v = "number" } end
    if c == "BACK" then return { k = "kb", v = "default" } end
    if c == "SETTINGS" then return { k = "cmd", v = "settings" } end
    if c == "Eisu_toggle" then return { k = "toggle", v = "ascii_mode" } end
    if c == "Mode_switch" then return { k = "toggle", v = "ascii_mode" } end
    if c == "Menu" or c == "VOICE_ASSIST" or c == "PROG_RED" or c == "function" then
      return { k = "none" }
    end
    return { k = "key", v = c }
  end

  if t == "table" then
    if c.select then return { k = "kb", v = c.select } end
    if c.text then return { k = "text", v = c.text } end
    if c.command then return cmd_of(c.command, c.option) end
    if c.toggle then return { k = "toggle", v = c.toggle } end
    if c.send ~= nil then
      local a = to_action(c.send)
      if a then return a end
    end
    if c.label then return { k = "none" } end
    return { k = "none" }
  end

  return { k = "none" }
end

local function composing_action(v)
  if v == "delimiterJ" then return { k = "cand", v = 2 } end
  if v == "delimiterS" then return { k = "cand", v = 3 } end
  return nil
end

---------------------------------------------------------------- 转换

local function basename(p) return (p:match("([^/]+)$") or p):gsub("%.lua$", "") end

local function convert(path)
  local env = {}
  local chunk, lerr = loadfile(path, "t", env)
  if not chunk then return nil, "load failed: " .. tostring(lerr) end
  local ok, err = pcall(chunk)
  if not ok then return nil, "eval failed: " .. tostring(err) end
  if not env.rows then return nil, "no rows (flex_box?)" end

  local id = basename(path)
  local nrows = #env.rows
  local kb = {
    id = id,
    name = env.name or id,
    keyWidth = env.key_width or 10,
    ascii = env.ascii_mode == true,
    lock = env.lock == true,
    rows = {},
  }

  for ri, row in ipairs(env.rows) do
    local keys = row.keys or {}
    local nk = #keys
    local h = row.height or env.key_height or (100 / nrows)
    local out = { h = h, keys = {} }
    local sum = 0
    for _, k in ipairs(keys) do
      local w = k.width or env.key_width or (100 / nk)
      sum = sum + w
      local label = k.label
      local hint = k.hint or ""
      -- trime2 的图标键面(ic_xxx)在 hexime 里没有图，退化成中文小字当键面
      if label and label:match("^ic_") then
        label = (hint ~= "" and hint) or ""
        hint = ""
      end
      local o = {
        w = w,
        label = label or "",
        hint = hint,
        click = to_action(k.click),
        long = to_action(k.long_click),
        up = to_action(k.swipe_up),
        down = to_action(k.swipe_down),
        left = to_action(k.swipe_left),
        right = to_action(k.swipe_right),
        ascii = to_action(k.ascii),
        -- composing = "delimiterJ/S": 组合中按它 = 选第 2/3 候选(不是覆盖 click)
        composing = k.composing and composing_action(k.composing) or nil,
      }
      if k.style then o.style = k.style end
      out.keys[#out.keys + 1] = o
    end
    out.sum = math.floor(sum * 100 + 0.5) / 100
    kb.rows[#kb.rows + 1] = out
  end
  return kb
end

---------------------------------------------------------------- main

local files = {}
local p = io.popen("ls " .. SRC .. "/*.lua 2>/dev/null")
if p then
  for l in p:lines() do files[#files + 1] = l end
  p:close()
end
table.sort(files)

os.execute("mkdir -p " .. OUT)
local done, skipped, badrows = 0, {}, {}
for _, f in ipairs(files) do
  local kb, err = convert(f)
  if not kb then
    skipped[#skipped + 1] = basename(f) .. " (" .. err .. ")"
  else
    local fp = io.open(OUT .. "/" .. kb.id .. ".json", "w")
    fp:write(jval(kb))
    fp:close()
    done = done + 1
    for ri, row in ipairs(kb.rows) do
      if math.abs(row.sum - 100) > 0.05 then
        badrows[#badrows + 1] = string.format("%s row%d sum=%.2f", kb.id, ri, row.sum)
      end
    end
  end
end

print(string.format("converted %d keyboards → %s", done, OUT))
if #skipped > 0 then print("skipped: " .. table.concat(skipped, ", ")) end
if #badrows > 0 then
  print(string.format("! 行宽 != 100%% 的行 %d 处:", #badrows))
  for _, s in ipairs(badrows) do print("   " .. s) end
end
