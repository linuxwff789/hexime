#!/usr/bin/env lua5.4
-- kbconvert.lua — 把键盘布局 lua 转成 hexime 的键盘 JSON。
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
-- 转换规则: 布局 lua 里的 preset/send 名字在转换期归一化成上面的 k，Kotlin 只做 dispatch。

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

-- UTF-8 字符数（#s 是字节数，中文会数错）
local function utf8_len(s) return utf8.len(s) or #s end

-- 按 UTF-8 字符截断
local function utf8_trunc(s, n)
  local len = utf8.len(s)
  if len and len <= n then return s end
  local i = utf8.offset(s, n + 1)
  return i and s:sub(1, i - 1) or s
end

---------------------------------------------------------------- action 归一化

-- 具名功能键：原样透传给引擎/服务端按 X11 keysym 或专用手势处理
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
    -- 单字节 ASCII → 按键（字母/数字/符号，交给 rime 处理）
    if #c == 1 and c:byte(1) < 128 then return { k = "sym", v = c } end
    local kp = c:match("^KP_(%d)$")
    if kp then return { k = "sym", v = kp } end
    -- 旧 preset 名 → hexime 键盘 id / 动作
    if c == "Keyboard_default" then return { k = "kb", v = "default" } end
    if c == "Keyboard_symbols" or c == "Keyboard_symbols_ext" then return { k = "kb", v = "symbols" } end
    if c == "Keyboard_number" then return { k = "kb", v = "number" } end
    if c == "BACK" then return { k = "kb", v = "default" } end
    if c == "SETTINGS" then return { k = "cmd", v = "settings" } end
    -- 选单的「配色」键：源布局里是换配色的广播，hexime 里 = 打开配色选择条
    if c == "PROG_RED" then return { k = "cmd", v = "palette" } end
    if c == "Eisu_toggle" or c == "Mode_switch" then return { k = "toggle", v = "ascii_mode" } end
    if c == "Menu" then return { k = "kb", v = "menu" } end -- Menu = rime 选单；hexime 里 = 打开选单键盘
    if c == "VOICE_ASSIST" then return { k = "none" } end   -- hexime 没有语音输入（功能缺失，图标仍在）
    if c == "function" then return { k = "none" } end        -- 带 command 的 function 已在 table 分支处理
    if c == "space1" or c == "Space" then return { k = "key", v = "space" } end
    if NAMED[c] then return { k = "key", v = c } end
    -- 其余都是「要上屏的文本」：小鹤的成对标点、音标、颜文字、emoji…
    -- （可能有 {Left} 这类按键宏，由 hexime 的 sendKeyMacro 拆）
    return { k = "text", v = c }
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

-- 键面上要显示的长按字符（画在键角上）
local function long_label(lc)
  if lc == nil then return nil end
  local t = type(lc)
  if t == "string" or t == "number" then return tostring(lc) end
  if t ~= "table" then return nil end
  local a = to_action(lc)
  if a and (a.k == "sym" or a.k == "text") and utf8_len(a.v) <= 2 then return a.v end
  if lc.label and utf8_len(lc.label) <= 3 then return lc.label end
  return nil
end

---------------------------------------------------------------- 转换

local function basename(p) return (p:match("([^/]+)$") or p):gsub("%.lua$", "") end

-- hexime 没实现的键：直接不生成（用户要求「没实现的开关去掉」）。
-- 按图标名匹配（布局里键面就是图标名），改了源文件也不会漏。
local SKIP_ICON = {
  ic_hint = "提示开关(未实现)",
  ic_note = "注解开关(未实现)",
  ic_candidate = "候选窗开关(未实现)",
  -- 用户要求从选单里去掉的三个：
  ic_theme = "主题(和「设置」重复)",
  ic_menu = "选单(就是本键盘)",
  ic_grid = "菜单2(并进选单)",
}

-- hexime 扩展：菜单2 并进选单后，把只存在于菜单2 的功能键补进来（否则「默认/助记」键盘切换就没入口了）。
local MENU_EXTRA = {
  { icon = "ic_keyboard", hint = "默认", click = { k = "kb", v = "default" }, style = "menu" },
  { icon = "ic_keyboard_star", hint = "助记", click = { k = "kb", v = "defaultVJ" }, style = "menu" },
  { icon = "ic_halfwidth", hint = "全半角", click = { k = "toggle", v = "full_shape" }, style = "menu" },
}

local dropped = {}

-- 无 label 的具名功能键 → 通用键面文字
local DEFAULT_LABEL = {
  BackSpace = "⌫", Return = "↵", space = "空格", Shift_L = "⇧", Shift_R = "⇧",
  Tab = "Tab", Delete = "Del", Escape = "Esc",
  Up = "↑", Down = "↓", Left = "←", Right = "→",
  copy = "复制", paste = "粘贴", cut = "剪切", undo = "撤销", redo = "重做", select_all = "全选",
  Keyboard_default = "返回", Keyboard_symbols = "符号", Keyboard_symbols_ext = "符号",
  Keyboard_number = "数字", Keyboard_letter = "abc",
}

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
    local keys0 = row.keys or {}
    local h = row.height or env.key_height or (100 / nrows)

    -- hexime 没实现的键直接不生成（用户要求「没实现的开关去掉」）。
    -- 选单/菜单2 是等宽 5 列，删掉 1 个会留个 20% 的洞 →
    -- 把这一行剩下的键按比例放宽，行宽仍然凑满 100%。
    local total = 0
    for _, k in ipairs(keys0) do total = total + (k.width or env.key_width or (100 / #keys0)) end
    local kept, gone = {}, {}
    for ki, k in ipairs(keys0) do
      local ct = type(k.click) == "table" and k.click or nil
      local ico = nil
      local lb = k.label or (ct and ct.label)
      if type(lb) == "string" and lb:match("^ic_") then ico = lb end
      if not ico and ct and type(ct.states) == "table" and type(ct.states[1]) == "string"
         and ct.states[1]:match("^ic_") then
        ico = ct.states[1]
      end
      -- 行首/行尾的纯占位（拿来错位用的 width-only 空键，例如 a 前面那个）。
      -- 用户要求：不要留空白，把这一行的键加宽铺满。行内的占位保留（布局需要）。
      -- 注意：必须「什么动作都没有」才算占位 —— 字母键是 click = "q"（字符串），
      -- 只看 click 是不是 table 会把整行字母当占位删掉。
      local hasAction = k.click ~= nil or k.long_click ~= nil or k.swipe_up ~= nil
        or k.swipe_down ~= nil or k.swipe_left ~= nil or k.swipe_right ~= nil
        or k.ascii ~= nil or k.composing ~= nil
      local isSpacer = not hasAction and lb == nil
        and (k.hint == nil or k.hint == "") and (k.icon == nil or k.icon == "")
      local atEdge = (ki == 1 or ki == #keys0)
      if ico and SKIP_ICON[ico] then
        gone[#gone + 1] = SKIP_ICON[ico]
      elseif isSpacer and atEdge then
        gone[#gone + 1] = "行" .. (ki == 1 and "首" or "尾") .. "占位"
      else
        kept[#kept + 1] = k
      end
    end
    local keys = keys0
    if #gone > 0 and #kept > 0 then
      local keepTotal = 0
      for _, k in ipairs(kept) do keepTotal = keepTotal + (k.width or env.key_width or 0) end
      if keepTotal > 0 then
        for _, k in ipairs(kept) do
          k.width = (k.width or env.key_width or 0) * total / keepTotal
        end
      end
      keys = kept
      dropped[#dropped + 1] = string.format("%s row%d 去掉 %s", tostring(env.name), ri, table.concat(gone, "、"))
    end
    local nk = #keys
    local out = { h = h, keys = {} }
    local sum = 0
    for _, k in ipairs(keys) do
      local w = k.width or env.key_width or (100 / nk)
      sum = sum + w
      local act = to_action(k.click)
      local label = k.label
      local hint = k.hint or ""
      local clickTable = type(k.click) == "table" and k.click or nil
      -- 小鹤(老 trime)把键面文字写在 click 表里: {click = {label = "返回", select = ...}}
      if label == nil and clickTable then label = clickTable.label end
      -- 还是没键面就按功能键名 / 字符给个通用键面（ascii.lua 这类无 label 的键盘）
      if label == nil and clickTable then label = DEFAULT_LABEL[clickTable.send] end
      if label == nil then label = DEFAULT_LABEL[k.click] end
      if label == nil and act and act.k == "sym" then label = act.v end
      if label == nil and act and act.k == "key" then label = DEFAULT_LABEL[act.v] end
      if label == nil and act and act.k == "toggle" and act.v == "ascii_mode" then label = "中英" end
      -- 符号/颜文字页：键面取它上屏的文本（截断到 4 个字，别把键撑爆）
      if label == nil and act and act.k == "text" then
        local plain = act.v:match("^([^{]*)") or ""
        if plain == "" then plain = act.v end
        label = utf8_trunc(plain, 4)
      end
      -- 图标键面(ic_xxx) 已转成 icon 字段（上面），键面文字留空

      local icon, iconAlt = nil, nil
      if label and label:match("^ic_") then
        icon = label
        label = ""
      end
      -- 状态类按键：states 写的是两个图标名（关/开），画当前状态的那个
      if clickTable and type(clickTable.states) == "table"
         and type(clickTable.states[1]) == "string" and clickTable.states[1]:match("^ic_") then
        icon = icon or clickTable.states[1]
        iconAlt = clickTable.states[2]
      end

      local o = {
        w = w,
        label = label or "",
        hint = hint,
        icon = icon or "",
        iconAlt = iconAlt or "",
        click = act,
        long = to_action(k.long_click),
        up = to_action(k.swipe_up),
        down = to_action(k.swipe_down),
        left = to_action(k.swipe_left),
        right = to_action(k.swipe_right),
        ascii = to_action(k.ascii),
        -- composing = "delimiterJ/S": 组合中按它 = 选第 2/3 候选(不是覆盖 click)
        composing = k.composing and composing_action(k.composing) or nil,
        -- 键角上显示的长按字符（从源布局带过来，hexime 也画出来）
        longLabel = long_label(k.long_click) or "",
      }
      if k.style then o.style = k.style end
      -- hexime 扩展：空格键上滑 = 加词（小鹤原本空格只绑了左右滑动=移光标）。
      -- 词取剪贴板、码取组合区；没有中文词/没编码时只提示，不写库。
      if o.style == "space" and o.up == nil then
        o.up = { k = "cmd", v = "addword" }
      end
      -- hexime 扩展：反查要用反引号触发（openfly 官方 recognizer 的 pattern）。
      -- 助记键盘 defaultVJ 的 z 长按是空动作 → 补上反引号（默认键盘 default 本来就有）。
      if o.click and o.click.k == "sym" and o.click.v == "z"
         and (o.long == nil or o.long.k == "none") then
        o.long = { k = "sym", v = "`" }
        o.longLabel = "`"
      end

      -- hexime 扩展：Shift 长按 = 按住（锁定 shift），再点/再长按解除。
      -- 小鹤原来的 Shift 只绑了 点击 + 上滑全选，长按空着，这里补上。
      if o.click and o.click.k == "key" and o.click.v == "Shift_L" then
        o.long = { k = "key", v = "Shift_Lock" }
        o.longLabel = "⇪"
      end

      -- hexime 扩展：选单里那个「语音」键（ic_mic / k=none）hexime 没有语音输入，
      -- 把这个位置改成「方案」——点一下循环切各输入方案。
      if o.icon == "ic_mic" then
        o.icon = "ic_keyboard"
        o.hint = "方案"
        o.label = ""
        o.click = { k = "cmd", v = "schema" }
      end
      out.keys[#out.keys + 1] = o
    end
    -- 凑不满 100% 的行按比例铺满（有些页面故意留几个点的缺口，看起来就是个豁口）
    if sum > 0 and math.abs(sum - 100) > 0.05 then
      local k100 = 100 / sum
      for _, o in ipairs(out.keys) do o.w = o.w * k100 end
      sum = 100
    end
    out.sum = math.floor(sum * 100 + 0.5) / 100
    kb.rows[#kb.rows + 1] = out
  end

  -- hexime 扩展：选单重排。原来 5x3 的选单去掉「主题/选单/菜单2」后只剩 12 个键，
  -- 再并进菜单2 的三个独有功能键（默认/助记/全半角），正好 15 个 → 重新铺成 5 列 x3 行，每行等宽铺满。
  if kb.id == "menu" then
    local all = {}
    for _, row in ipairs(kb.rows) do
      for _, k in ipairs(row.keys) do all[#all + 1] = k end
    end
    for _, k in ipairs(MENU_EXTRA) do all[#all + 1] = k end
    -- 「返回」放最后一个（用户要求）
    for i, k in ipairs(all) do
      if k.icon == "ic_back" then
        local back = table.remove(all, i)
        all[#all + 1] = back
        break
      end
    end
    local per = 5
    local nrows = math.ceil(#all / per)
    local rows = {}
    for i = 1, #all, per do
      local keys = {}
      for j = i, math.min(i + per - 1, #all) do
        local k = all[j]
        k.w = 100 / per
        k.style = k.style or "menu"
        keys[#keys + 1] = k
      end
      rows[#rows + 1] = { h = 100 / nrows, keys = keys, sum = 100 }
    end
    kb.rows = rows
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
    if kb.id == "menux" then
      skipped[#skipped + 1] = "menux (已并进选单)"
    else
      local fp = io.open(OUT .. "/" .. kb.id .. ".json", "w")
      fp:write(jval(kb))
      fp:close()
      done = done + 1
    end
    for ri, row in ipairs(kb.rows) do
      if math.abs(row.sum - 100) > 0.05 then
        badrows[#badrows + 1] = string.format("%s row%d sum=%.2f", kb.id, ri, row.sum)
      end
    end
  end
end

print(string.format("converted %d keyboards → %s", done, OUT))
if #skipped > 0 then print("skipped: " .. table.concat(skipped, ", ")) end
if #dropped > 0 then
  print(string.format("! 按 SKIP_ICON 去掉的键 %d 处（hexime 没实现）:", #dropped))
  for _, s in ipairs(dropped) do print("   " .. s) end
end
if #badrows > 0 then
  print(string.format("! 行宽 != 100%% 的行 %d 处:", #badrows))
  for _, s in ipairs(badrows) do print("   " .. s) end
end
