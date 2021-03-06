import React from 'react';
import PropTypes from 'prop-types';
import {Input, MenuItem, Select} from "@material-ui/core";

/**
 * a simple edit-box-and-multiplier selector component for inputting data sizes
 */
class SizeInput extends React.Component {
    static propTypes = {
        sizeInBytes:PropTypes.number.isRequired,
        didUpdate:PropTypes.func.isRequired,
        minimumMultiplier: PropTypes.number
    };

    static multipliers = [
        { label: "bytes", value: 1 },
        { label: "Kb", value: 1024 },
        { label: "Mb", value: 1048576},
        { label: "Gb", value: 1073741824},
        { label: "Tb", value: 1099511627776}
    ];

    constructor(props){
        super(props);

        this.state = {
            value: 0,
            multiplier: props.minimumMultiplier ? props.minimumMultiplier : 0
        };

        this.numberUpdated = this.numberUpdated.bind(this);
        this.multiplierUpdated = this.multiplierUpdated.bind(this);
        this.updateParent = this.updateParent.bind(this);
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        if(prevProps.sizeInBytes!==this.props.sizeInBytes) this.setInternalValues();
    }

    componentDidMount() {
        this.setInternalValues();
    }

    checkNextMultiplier(value, multiplier){
        return value/multiplier.value;
    }

    multiplierIndexForValue(value){
        for(let i=0;i<SizeInput.multipliers.length; ++i){
            if(SizeInput.multipliers[i].value>=value) return i;
        }
        return 0;
    }

    setInternalValues(){
        if(this.props.sizeInBytes===null) {
            this.setState({value: 0, multiplier: this.props.minimumMultiplier ? this.multiplierIndexForValue(this.props.minimumMultiplier) : 0});
            return;
        }
        let updatedValue;
        const minimumMultiplierIdx = this.multiplierIndexForValue(this.props.minimumMultiplier);

        for(let c=0;c<SizeInput.multipliers.length;++c){
            updatedValue = this.checkNextMultiplier(this.props.sizeInBytes, SizeInput.multipliers[c]);
            if(updatedValue<1024){
                this.setState({value: updatedValue, multiplier: c<minimumMultiplierIdx ? minimumMultiplierIdx : c });
                break;
            }
        }
        if(updatedValue>1024){
            this.setState({value: updatedValue, multiplier: SizeInput.multipliers.length-1})
        }
    }

    updateParent(){
        this.props.didUpdate(this.state.value * SizeInput.multipliers[this.state.multiplier].value);
    }

    numberUpdated(evt){
        this.setState({value: evt.target.value}, this.updateParent);
    }

    multiplierUpdated(evt){
        this.setState({multiplier: evt.target.value}, this.updateParent);
    }

    render() {
        return <span>
            <Input type="number" onChange={this.numberUpdated} value={this.state.value} style={{width: "50px", marginRight: "1em"}}/>
            <Select onChange={this.multiplierUpdated} value={this.state.multiplier}>
                {
                    SizeInput.multipliers
                        .map((mul,idx)=><MenuItem key={idx} value={idx}>{mul.label}</MenuItem>)
                }
            </Select>
        </span>
    }
}

export default SizeInput;