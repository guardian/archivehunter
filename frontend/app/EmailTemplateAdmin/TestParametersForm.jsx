import React from "react";
import PropTypes from "prop-types";
import AssociationSelector from "./AssociationSelector.jsx";

class TestParametersForm extends React.Component {
    static propTypes = {
        paramList: PropTypes.array.isRequired,
        onChange: PropTypes.func.isRequired,
        className: PropTypes.string,
        forEvent: PropTypes.string,
        onEventChanged: PropTypes.func.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            paramValues: {}
        }
    }

    updateParamValue(paramName, newValue) {
        let updateDict = {};
        updateDict[paramName] = newValue;

        this.setState({paramValues: Object.assign(this.state.paramValues, updateDict)}, ()=>this.props.onChange(this.state.paramValues));
    }

    render(){
        return <table className={this.props.className}>
            <tbody>
            <tr>
                <td>Simulated event</td>
                <td><AssociationSelector onChange={this.props.onEventChanged} value={this.props.forEvent} forTemplate=""/></td>
            </tr>
            {
                this.props.paramList.map(paramName=><tr key={paramName}>
                    <td>{paramName}</td>
                    <td><input value={this.state.paramValues.hasOwnProperty(paramName) ? this.state.paramValues[paramName] : ""}
                               onChange={evt=>this.updateParamValue(paramName, evt.target.value)}
                    /> </td>
                </tr>)
            }
            </tbody>
        </table>
    }
}

export default TestParametersForm;