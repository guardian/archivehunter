import React from 'react';
import PropTypes from 'prop-types';

class GenericDropdown extends React.Component {
    static propTypes = {
        valueList: PropTypes.array.isRequired,
        onChange: PropTypes.func.isRequired,
        value: PropTypes.string.isRequired,
        includeNull: PropTypes.bool,
        nullName: PropTypes.string
    };

    constructor(props){
        super(props);

        this.internalValueChanged = this.internalValueChanged.bind(this);
    }

    makeNewEvent(oldEvt, newValue){
        const newTarget = Object.assign({}, oldEvt.target, {value: newValue});
        const newEvt = Object.assign({}, oldEvt, {target: newTarget});
        return newEvt;
    }

    internalValueChanged(evt){
        if(this.props.nullName && evt.target.value===this.props.nullName){
            this.props.onChange(this.makeNewEvent(evt, null));
        } else if(!this.props.nullName && evt.target.value==="none"){
            this.props.onChange(this.makeNewEvent(evt, null));
        } else {
            this.props.onChange(evt);
        }
    }

    render(){
        return <select onChange={this.internalValueChanged} value={this.props.value}>
            {
                this.props.includeNull ? <option key="none" value={null}>{this.props.nullName ? this.props.nullName : "none"}</option> : ""
            }
            {
                this.props.valueList.map(entry=><option key={entry} value={entry}>{entry}</option>)
            }
        </select>
    }
}

export default GenericDropdown;