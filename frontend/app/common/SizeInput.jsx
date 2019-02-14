import React from 'react';
import PropTypes from 'prop-types';

/**
 * a simple edit-box-and-multiplier selector component for inputting data sizes
 */
class SizeInput extends React.Component {
    static propTypes = {
        sizeInBytes:PropTypes.number.isRequired,
        didUpdate:PropTypes.func.isRequired,
        minumumMultiplier: PropTypes.number
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
            multiplier: 0
        };

        this.numberUpdated = this.numberUpdated.bind(this);
        this.multiplierUpdated = this.multiplierUpdated.bind(this);
        this.updateParent = this.updateParent.bind(this);
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        if(prevProps.sizeInBytes!==this.props.sizeInBytes) this.setInternalValues();
    }

    componentWillMount() {
        this.setInternalValues();
    }

    checkNextMultiplier(value, multiplier){
        return value/multiplier.value;
    }

    setInternalValues(){
        let updatedValue;
        for(let c=0;c<SizeInput.multipliers.length;++c){
            updatedValue = this.checkNextMultiplier(this.props.sizeInBytes, SizeInput.multipliers[c]);
            console.log(this.props.sizeInBytes, updatedValue, c);
            if(updatedValue<1024){
                this.setState({value: updatedValue, multiplier: c});
                break;
            }
        }
        if(updatedValue>1024){
            this.setState({value: updatedValue, multiplier: SizeInput.multipliers.length-1})
        }
    }

    updateParent(){
        //console.log("update parent, value ", this.state.value, " multiplier index ", this.state.multiplier);
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
            <input type="number" onChange={this.numberUpdated} value={this.state.value}/>
            <select onChange={this.multiplierUpdated} value={this.state.multiplier}>
                {
                    SizeInput.multipliers.map((mul,idx)=><option key={idx} value={idx}>{mul.label}</option>)
                }
            </select>
        </span>
    }
}

export default SizeInput;