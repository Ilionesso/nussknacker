import _ from 'lodash';

export default class ExpressionSuggester {

  constructor(typesInformation, variables) {
    this._typesInformation = typesInformation
    this._variables = _.mapKeys(variables, (value, variableName) => {return `#${variableName}`})
  }

  suggestionsFor = (inputValue, caretPosition2d) => {
    const normalized = this._normalizeMultilineInputToSingleLine(inputValue, caretPosition2d)
    const lastExpressionPart = this._focusedLastExpressionPartWithoutMethodParens(normalized.normalizedInput, normalized.normalizedCaretPosition)
    const properties = this._alreadyTypedProperties(lastExpressionPart)
    const focusedClazz = this._findRootClazz(properties)
    return this._getSuggestions(lastExpressionPart, focusedClazz)
  }

  _normalizeMultilineInputToSingleLine = (inputValue, caretPosition2d) => {
    const rows = inputValue.split("\n")
    const trimmedRows = _.map(rows, (row) => {
      const trimmedAtStartRow = _.dropWhile(row, (c) => c === " ").join("")
      return { trimmedAtStartRow: trimmedAtStartRow, trimmedCount: row.length - trimmedAtStartRow.length }
    })
    const beforeCaretInputLength = _.sum(_.map(_.take(trimmedRows, caretPosition2d.row), (row) => row.trimmedAtStartRow.length));
    const normalizedCaretPosition = caretPosition2d.column - trimmedRows[caretPosition2d.row].trimmedCount + beforeCaretInputLength
    const normalizedInput = _.map(trimmedRows, (row) => row.trimmedAtStartRow).join("")
    return {
      normalizedInput: normalizedInput,
      normalizedCaretPosition: normalizedCaretPosition
    }
  }

  _getSuggestions = (value, focusedClazz) => {
    const variableNames = _.keys(this._variables)
    const variableAlreadySelected = _.some(variableNames, (variable) => { return _.includes(value, `${variable}.`) })
    const variableNotSelected = _.some(variableNames, (variable) => { return _.startsWith(variable.toLowerCase(), value.toLowerCase()) })
    if (variableAlreadySelected && focusedClazz) {
      const currentType = this._getTypeInfo(focusedClazz)
      const inputValue = this._justTypedProperty(value)
      const allowedMethodList = _.map(currentType.methods, (val, key) => { return { ...val, methodName: key} })
      return inputValue.length === 0 ? allowedMethodList : this._filterSuggestionsForInput(allowedMethodList, inputValue)
    } else if (variableNotSelected && !_.isEmpty(value)) {
      const allVariablesWithClazzRefs = _.map(this._variables, (val, key) => {
        return {'methodName': key, 'refClazzName': val.refClazzName}
      })
      return this._filterSuggestionsForInput(allVariablesWithClazzRefs, value)
    }
    else {
      return []
    }
  }

  _filterSuggestionsForInput = (methods, inputValue) => {
    return _.filter(methods, (method) => {
      return _.includes(method.methodName.toLowerCase(), inputValue.toLowerCase())
    })
  }

  _findRootClazz = (properties) => {
    const variableName = properties[0]
    if (_.has(this._variables, variableName)) {
      const variableClazzName = _.get(this._variables, variableName).refClazzName
      return _.reduce(_.tail(properties), (currentParentClazz, prop) => {
        const parentClazz = this._getTypeInfo(currentParentClazz)
        return _.get(parentClazz.methods, `${prop}.refClazzName`) || ""
      }, variableClazzName)
    } else {
      return null
    }
  }

  _getTypeInfo = (clazzName) => {
    const typeInfo = _.find(this._typesInformation, { clazzName: { refClazzName: clazzName }})
    return !_.isEmpty(typeInfo) ? typeInfo : {
      clazzName: {
        refClazzName: clazzName,
        methods: []
      }}
  }

  _focusedLastExpressionPartWithoutMethodParens = (expression, caretPosition) => {
    return this._lastExpressionPartWithoutMethodParens(this._currentlyFocusedExpressionPart(expression, caretPosition))
  }

  _currentlyFocusedExpressionPart = (value, caretPosition) => {
    return value.slice(0, caretPosition)
  }

  _lastExpressionPartWithoutMethodParens = (value) => {
    const valueCleaned = this._removeMethodParensFromProperty(value)
    return _.isEmpty(value) ? "" : "#" + _.last(_.split(valueCleaned, '#'))
  }

  _justTypedProperty = (value) => {
    return _.last(this._dotSeparatedToProperties(value))
  }

  _alreadyTypedProperties = (value) => {
    return _.initial(this._dotSeparatedToProperties(value))
  }

  _dotSeparatedToProperties = (value) => {
    return _.split(value, ".")
  }

  _removeMethodParensFromProperty = (property) => {
    return property.replace(/\(.*\)/, "")
  }

}