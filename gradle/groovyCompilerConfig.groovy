withConfig(configuration) {
    imports {
        star 'groovy.transform'
    }
    inline {
        turn off: 'CompileStatic'
    }
}
